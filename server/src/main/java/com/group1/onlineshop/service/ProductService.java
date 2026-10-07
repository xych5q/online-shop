package com.group1.onlineshop.service;

import com.group1.onlineshop.entity.Intent;
import com.group1.onlineshop.entity.Product;
import com.group1.onlineshop.exception.BizException;
import com.group1.onlineshop.repository.IntentRepository;
import com.group1.onlineshop.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 商品服务：发布、冻结/解冻、进入交易、标记交易结果、手动下架、失败意向处置。
 * 业务规则逐条翻译自原型 prototype/single-seller-queue-trade/server.js 与 docs/design/接口设计.md，规则未做改动。
 */
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final IntentRepository intentRepository;

    /** 上传目录（application.yml: app.upload.dir = ./data/uploads） */
    @Value("${app.upload.dir}")
    private String uploadDir;

    /** 上传文件序号（文件名去重） */
    private final AtomicLong uploadSeq = new AtomicLong();

    /** data:image/(jpeg|png);base64,xxx 图片入参格式 */
    private static final Pattern DATA_URL_PATTERN =
            Pattern.compile("^data:(image/(jpeg|png));base64,(.+)$");

    /** 图片大小上限 5MB（与 multipart 配置一致） */
    private static final int MAX_IMAGE_BYTES = 5 * 1024 * 1024;

    /**
     * 发布商品。
     * 业务规则：已有在售或冻结商品 → 400「已有商品在售或交易中，需先下架」；
     * name 去空格后必填且 ≤100 字；description ≤2000 字；price 必须为 >0 的数字且保留两位小数（BigDecimal HALF_UP）；
     * image 可选，仅支持 data:image/(jpeg|png);base64,... 且 ≤5MB，解码后存到 app.upload.dir，只把文件名存进 Product.image；
     * 发布成功后商品状态直接为在售（无独立「上架」动作）；商品发布后不可修改。
     */
    public Map<String, Object> publish(Map<String, Object> body) {
        if (productRepository.existsByStatusIn(List.of(Product.Status.ON_SALE, Product.Status.FROZEN))) {
            throw new BizException("已有商品在售或交易中，需先下架");
        }
        String name = str(body.get("name")).trim();
        String description = str(body.get("description")).trim();
        BigDecimal price;
        try {
            price = body.get("price") == null ? null
                    : new BigDecimal(str(body.get("price"))).setScale(2, RoundingMode.HALF_UP);
        } catch (NumberFormatException e) {
            price = null;
        }
        if (name.isEmpty()) {
            throw new BizException("商品名称必填");
        }
        if (name.length() > 100) {
            throw new BizException("商品名称不能超过 100 字");
        }
        if (description.length() > 2000) {
            throw new BizException("商品描述不能超过 2000 字");
        }
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("价格必须为大于 0 的数字");
        }
        String image = null;
        if (body.get("image") != null) {
            image = saveImage(str(body.get("image")));
        }

        Product product = new Product();
        product.setName(name);
        product.setDescription(description);
        product.setPrice(price);
        product.setImage(image);
        product.setStatus(Product.Status.ON_SALE);
        productRepository.save(product);
        return ok();
    }

    /**
     * 手动冻结商品。
     * 业务规则：商品不存在 → 404「商品不存在」；已下架 → 400「商品已下架」；
     * 仅「在售」可手动冻结 → 否则 400「仅「在售」商品可手动冻结」；
     * 冻结后 status=FROZEN、frozenBy='manual'、frozenAt=now。
     */
    public Map<String, Object> freeze(long id) {
        Product product = loadActiveProduct(id);
        if (product.getStatus() != Product.Status.ON_SALE) {
            throw new BizException("仅「在售」商品可手动冻结");
        }
        product.setStatus(Product.Status.FROZEN);
        product.setFrozenBy("manual");
        product.setFrozenAt(LocalDateTime.now());
        productRepository.save(product);
        return ok();
    }

    /**
     * 手动解冻商品。
     * 业务规则：未处于冻结状态 → 400「商品未处于冻结状态」；
     * 仅 frozenBy='manual'（手动冻结）可解冻，交易冻结（frozenBy='auto'）必须先标记交易结果，
     * 否则 400「交易中的商品不能手动解冻」；解冻后恢复在售并清空 frozenBy/frozenAt。
     */
    public Map<String, Object> unfreeze(long id) {
        Product product = loadActiveProduct(id);
        if (product.getStatus() != Product.Status.FROZEN) {
            throw new BizException("商品未处于冻结状态");
        }
        if (!"manual".equals(product.getFrozenBy())) {
            throw new BizException("交易中的商品不能手动解冻");
        }
        product.setStatus(Product.Status.ON_SALE);
        product.setFrozenBy(null);
        product.setFrozenAt(null);
        productRepository.save(product);
        return ok();
    }

    /**
     * 与队首买家进入交易。
     * 业务规则：仅「在售」商品可进入交易 → 否则 400「仅「在售」商品可进入交易」；
     * 取队列首位（按提交时间正序，卖家不可挑人）置为 IN_TRANSACTION，商品自动置 FROZEN、frozenBy='auto'、frozenAt=now；
     * 队列为空 → 400「当前没有排队的买家」。
     */
    public Map<String, Object> deal(long id) {
        Product product = loadActiveProduct(id);
        if (product.getStatus() != Product.Status.ON_SALE) {
            throw new BizException("仅「在售」商品可进入交易");
        }
        enterDealTemp(product); // TODO: 待 TradeService 就绪后改为调用 tradeService.enterDeal(product)
        return ok();
    }

    /**
     * 标记交易结果。
     * 业务规则：仅交易冻结中（FROZEN 且 frozenBy='auto'）可标记 → 否则 400「当前没有进行中的交易」；
     * result 只接受 success / fail，其他值 400「参数错误」。
     * success：胜出意向置 SUCCESS 且 disposed=true，队列剩余全部转 FAILED 且 disposed=true，商品下架进历史（closedAt=now），所有口令码随之失效；
     * fail：当前意向转 FAILED（disposed=false，待卖家处置）；队列有人 → 下一位自动递补进入交易（商品保持冻结）；队列无人 → 商品自动恢复在售。
     */
    public Map<String, Object> dealResult(long id, String result) {
        Product product = loadActiveProduct(id);
        if (product.getStatus() != Product.Status.FROZEN || !"auto".equals(product.getFrozenBy())) {
            throw new BizException("当前没有进行中的交易");
        }
        if (!"success".equals(result) && !"fail".equals(result)) {
            throw new BizException("参数错误");
        }
        markResultTemp(product, result); // TODO: 待 TradeService 就绪后改为调用 tradeService.markResult(product, result)
        return ok();
    }

    /**
     * 手动下架。
     * 业务规则：交易冻结中（FROZEN 且 frozenBy='auto'）须先标记交易结果 → 400「交易中的商品请先标记交易结果」；
     * 下架时该商品队列里所有排队中意向置 FAILED 且 disposed=true，待处置失败意向也全部置 disposed=true，商品置 OFF_SHELF、closedAt=now。
     */
    public Map<String, Object> offshelf(long id) {
        Product product = loadActiveProduct(id);
        if (product.getStatus() == Product.Status.FROZEN && !"manual".equals(product.getFrozenBy())) {
            throw new BizException("交易中的商品请先标记交易结果");
        }
        for (Intent it : intentRepository
                .findByProductIdAndStatusOrderBySubmittedAtAscIdAsc(product.getId(), Intent.Status.QUEUED)) {
            it.setStatus(Intent.Status.FAILED);
            it.setDisposed(true);
            intentRepository.save(it);
        }
        for (Intent it : intentRepository
                .findByProductIdAndStatusAndDisposedFalseOrderBySubmittedAtAsc(product.getId(), Intent.Status.FAILED)) {
            it.setDisposed(true);
            intentRepository.save(it);
        }
        product.setStatus(Product.Status.OFF_SHELF);
        product.setClosedAt(LocalDateTime.now());
        productRepository.save(product);
        return ok();
    }

    /**
     * 处置失败意向。
     * 业务规则：意向不存在 → 404「意向不存在」；仅处置 FAILED 且未处置过的意向 → 否则 400「该意向无需处理」；
     * action 只接受 void / requeue，其他值 400「参数错误」；
     * 商品已下架时无法处置 → 400「商品已下架，无法处理」；
     * void：仅作废（disposed=true）；requeue：生成新排队记录，沿用原口令码（不换码）、submittedAt 刷新为当前时间（排到队尾）、requeuedFrom 指向原意向。
     */
    public Map<String, Object> dispose(long intentId, String action) {
        Intent intent = intentRepository.findById(intentId)
                .orElseThrow(() -> new BizException("意向不存在", 404));
        if (intent.getStatus() != Intent.Status.FAILED || intent.isDisposed()) {
            throw new BizException("该意向无需处理");
        }
        if (!"void".equals(action) && !"requeue".equals(action)) {
            throw new BizException("参数错误");
        }
        disposeFailedTemp(intent, action); // TODO: 待 TradeService 就绪后改为调用 tradeService.disposeFailed(intent, action)
        return ok();
    }

    /**
     * 买家侧当前商品（无鉴权）。
     * 业务规则：只返回在售或冻结的商品，无则返回 null（前端显示「暂无商品在售」）；不返回任何意向或口令码信息。
     */
    public Map<String, Object> activeProduct() {
        return productRepository
                .findFirstByStatusInOrderByCreatedAtAsc(List.of(Product.Status.ON_SALE, Product.Status.FROZEN))
                .map(this::publicProduct)
                .orElse(null);
    }

    /** 商品对外展示字段（与原型 publicProduct 一致） */
    private Map<String, Object> publicProduct(Product p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("description", p.getDescription());
        m.put("price", p.getPrice());
        m.put("imageUrl", p.getImage() == null ? null : "/uploads/" + p.getImage());
        m.put("status", p.getStatus().name().toLowerCase());
        m.put("publishedAt", p.getCreatedAt());
        return m;
    }

    /** 按 id 加载未下架商品：不存在抛 404，已下架抛 400（与原型路由行为一致） */
    private Product loadActiveProduct(long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new BizException("商品不存在", 404));
        if (product.getStatus() == Product.Status.OFF_SHELF) {
            throw new BizException("商品已下架");
        }
        return product;
    }

    /**
     * 与队首进入交易（临时实现，规则与原型 enterDeal 一致）。
     * TODO: 待 TradeService 就绪后改为调用 tradeService.enterDeal(product)，删除本方法
     */
    private void enterDealTemp(Product product) {
        List<Intent> queue = intentRepository
                .findByProductIdAndStatusOrderBySubmittedAtAscIdAsc(product.getId(), Intent.Status.QUEUED);
        if (queue.isEmpty()) {
            throw new BizException("当前没有排队的买家");
        }
        Intent head = queue.get(0);
        head.setStatus(Intent.Status.IN_TRANSACTION);
        intentRepository.save(head);
        product.setStatus(Product.Status.FROZEN);
        product.setFrozenBy("auto");
        product.setFrozenAt(LocalDateTime.now());
        productRepository.save(product);
    }

    /**
     * 标记交易结果（临时实现，规则与原型 markResult 一致）。
     * TODO: 待 TradeService 就绪后改为调用 tradeService.markResult(product, result)，删除本方法
     */
    private void markResultTemp(Product product, String result) {
        Intent deal = intentRepository
                .findByProductIdAndStatus(product.getId(), Intent.Status.IN_TRANSACTION)
                .orElseThrow(() -> new BizException("当前没有进行中的交易"));
        if ("success".equals(result)) {
            deal.setStatus(Intent.Status.SUCCESS);
            deal.setDisposed(true);
            intentRepository.save(deal);
            for (Intent it : intentRepository
                    .findByProductIdAndStatusOrderBySubmittedAtAscIdAsc(product.getId(), Intent.Status.QUEUED)) {
                it.setStatus(Intent.Status.FAILED);
                it.setDisposed(true);
                intentRepository.save(it);
            }
            product.setStatus(Product.Status.OFF_SHELF);
            product.setClosedAt(LocalDateTime.now());
            productRepository.save(product);
            return;
        }
        // fail：当前意向转失败待处置
        deal.setStatus(Intent.Status.FAILED);
        deal.setDisposed(false);
        intentRepository.save(deal);
        List<Intent> queue = intentRepository
                .findByProductIdAndStatusOrderBySubmittedAtAscIdAsc(product.getId(), Intent.Status.QUEUED);
        if (!queue.isEmpty()) {
            // 队列有人 → 下一位自动递补进入交易（无需卖家再次点击），商品保持冻结
            queue.get(0).setStatus(Intent.Status.IN_TRANSACTION);
            intentRepository.save(queue.get(0));
            product.setFrozenAt(LocalDateTime.now());
            productRepository.save(product);
        } else {
            // 队列无人 → 商品自动恢复在售
            product.setStatus(Product.Status.ON_SALE);
            product.setFrozenBy(null);
            product.setFrozenAt(null);
            productRepository.save(product);
        }
    }

    /**
     * 处置失败意向（临时实现，规则与原型 disposeFailed 一致）。
     * TODO: 待 TradeService 就绪后改为调用 tradeService.disposeFailed(intent, action)，删除本方法
     */
    private void disposeFailedTemp(Intent intent, String action) {
        Product product = productRepository.findById(intent.getProductId()).orElse(null);
        if (product == null || product.getStatus() == Product.Status.OFF_SHELF) {
            throw new BizException("商品已下架，无法处理");
        }
        intent.setDisposed(true);
        if ("requeue".equals(action)) {
            Intent requeued = new Intent();
            requeued.setProductId(intent.getProductId());
            requeued.setName(intent.getName());
            requeued.setPhone(intent.getPhone());
            requeued.setCode(intent.getCode()); // 重新排队不生成新口令码
            requeued.setSubmittedAt(LocalDateTime.now()); // 提交时间刷新 → 排到队尾
            requeued.setStatus(Intent.Status.QUEUED);
            requeued.setRequeuedFrom(intent.getId());
            requeued.setDisposed(false);
            // 原型规则：重新排队沿用原口令码、原记录保留作历史流水。
            // 但 entity 中 Intent.code 定义了唯一约束（unique=true），两行不能同码：
            // 由于口令码只对未终态意向有效（买家 lookup 只认未终态，历史流水不返回口令码），
            // 将已终态的原记录口令码改为带后缀的占位值、新记录沿用原码，对外行为与原型完全一致。
            intent.setCode(intent.getCode() + "#r" + intent.getId());
            intentRepository.save(intent);
            intentRepository.save(requeued);
        } else {
            intentRepository.save(intent);
        }
    }

    /**
     * 保存图片：校验 data URL 格式与大小，解码后写入上传目录，返回文件名。
     * 业务规则：仅支持 JPG/PNG；内容为空或超过 5MB 均拒绝；文件名 img_<时间戳>_<序号>.<扩展名>。
     */
    private String saveImage(String dataUrl) {
        Matcher m = DATA_URL_PATTERN.matcher(dataUrl);
        if (!m.matches()) {
            throw new BizException("图片格式仅支持 JPG / PNG");
        }
        byte[] buf;
        try {
            buf = Base64.getDecoder().decode(m.group(3));
        } catch (IllegalArgumentException e) {
            throw new BizException("图片格式仅支持 JPG / PNG");
        }
        if (buf.length == 0) {
            throw new BizException("图片内容为空");
        }
        if (buf.length > MAX_IMAGE_BYTES) {
            throw new BizException("图片大小不能超过 5MB");
        }
        String ext = "jpeg".equals(m.group(2)) ? "jpg" : "png";
        String name = "img_" + System.currentTimeMillis() + "_" + uploadSeq.incrementAndGet() + "." + ext;
        try {
            Files.createDirectories(Path.of(uploadDir));
            Files.write(Path.of(uploadDir, name), buf);
        } catch (IOException e) {
            throw new BizException("服务器内部错误", 500);
        }
        return name;
    }

    /** null 安全的字符串转换 */
    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    /** 统一成功返回 {ok: true} */
    private Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }
}
