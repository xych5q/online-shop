package com.group1.onlineshop.service;

import com.group1.onlineshop.dto.ProductPublishRequest;
import com.group1.onlineshop.entity.Category;
import com.group1.onlineshop.entity.Intent;
import com.group1.onlineshop.entity.Product;
import com.group1.onlineshop.entity.ProductImage;
import com.group1.onlineshop.exception.BizException;
import com.group1.onlineshop.repository.CategoryRepository;
import com.group1.onlineshop.repository.IntentRepository;
import com.group1.onlineshop.repository.ProductImageRepository;
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
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 商品服务：发布、冻结/解冻、进入交易、标记交易结果、手动下架、失败意向处置。
 * 发布接口按「多商品发布」需求实现（类别/库存/多图/富文本描述，见 publish 方法注释），
 * 其余交易规则逐条翻译自原型 prototype/single-seller-queue-trade/server.js 与 docs/design/接口设计.md，规则未做改动。
 */
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository productRepository;
    private final IntentRepository intentRepository;
    private final CategoryRepository categoryRepository;
    private final ProductImageRepository productImageRepository;
    private final TradeService tradeService;

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
     * 发布商品（多商品发布接口，对齐挑战需求 A 数据模型）。
     * 业务规则（需求迭代）：
     *   1. 类别必填：categoryId 缺失 → 400「商品类别必填」；类别不存在 → 400「类别不存在」；
     *      一级类别不可挂商品（A.1.2-2 必挂二级）→ 400「商品必须挂到二级类别」；
     *   2. 库存校验：stock 必须 ≥1（0 或负数）→ 400「库存必须大于或等于 1」；
     *   3. 多图限制：images 必须 1~9 张 → 400「图片数量必须在 1 到 9 张之间」；
     *   4. 富文本描述：richDescription 支持 HTML/Markdown 长文本，≤8000 字（description 简要 ≤2000 字）；
     *   5. 其余沿用原规则：name 去空格后必填且 ≤100 字；price 必须 >0 且保留两位小数（HALF_UP）；
     *   6. 取消「同一时间只允许一件在售商品」限制，允许多商品并存（A.1.1-1）；
     *   7. 图片沿用 data:image/(jpeg|png);base64 约定（单张 ≤5MB），解码落盘，
     *      文件名逐条写入 product_image 表（sortOrder 从 0 起，最小者为主图，与迁移脚本约定一致），
     *      并把主图文件名写入 Product.image 兼容旧展示字段；
     * 发布成功后商品状态直接为在售（无独立「上架」动作）；商品发布后不可修改（A.1.1-3）。
     *
     * DTO 注解校验失败时由 GlobalExceptionHandler 先行拦截（400 + 第一条错误文案），
     * 本方法内的校验为服务层二次防御（绕过 DTO 直接调用时同样生效）。
     */
    public Map<String, Object> publish(ProductPublishRequest req) {
        String name = req.getName() == null ? "" : req.getName().trim();
        Long categoryId = req.getCategoryId();
        String description = req.getDescription() == null ? "" : req.getDescription().trim();
        String richDescription = req.getRichDescription() == null ? "" : req.getRichDescription().trim();
        BigDecimal price = req.getPrice() == null ? null
                : req.getPrice().setScale(2, RoundingMode.HALF_UP);
        Integer stock = req.getStock();
        List<String> images = req.getImages();

        // —— 服务层核心校验（与 DTO 注解规则一致，双保险）——
        if (name.isEmpty()) {
            throw new BizException("商品名称必填");
        }
        if (name.length() > 100) {
            throw new BizException("商品名称不能超过 100 字");
        }
        if (categoryId == null) {
            throw new BizException("商品类别必填");
        }
        Category category = categoryRepository.findById(categoryId)
                .orElseThrow(() -> new BizException("类别不存在"));
        if (category.getParentId() == null) {
            throw new BizException("商品必须挂到二级类别");
        }
        if (description.length() > 2000) {
            throw new BizException("商品描述不能超过 2000 字");
        }
        if (richDescription.length() > 8000) {
            throw new BizException("商品详情不能超过 8000 字");
        }
        if (price == null || price.compareTo(BigDecimal.ZERO) <= 0) {
            throw new BizException("价格必须为大于 0 的数字");
        }
        if (stock == null || stock < 1) {
            throw new BizException("库存必须大于或等于 1");
        }
        if (images == null || images.size() < 1 || images.size() > 9) {
            throw new BizException("图片数量必须在 1 到 9 张之间");
        }

        Product product = new Product();
        product.setName(name);
        product.setCategoryId(categoryId);
        product.setDescription(description);
        product.setRichDescription(richDescription);
        product.setPrice(price);
        product.setStock(stock);
        product.setStatus(Product.Status.ON_SALE);
        productRepository.save(product);

        // 逐张保存图片并写入 product_image（sortOrder 从 0 开始，最小者即主图）
        List<ProductImage> imageList = new ArrayList<>();
        for (int i = 0; i < images.size(); i++) {
            ProductImage pi = new ProductImage();
            pi.setProductId(product.getId());
            pi.setFilename(saveImage(images.get(i)));
            pi.setSortOrder(i);
            imageList.add(pi);
        }
        productImageRepository.saveAll(imageList);
        // 兼容旧展示字段：主图文件名写入 Product.image
        product.setImage(imageList.get(0).getFilename());
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
        tradeService.enterDeal(product);
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
        tradeService.markResult(product, result);
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
        tradeService.disposeFailed(intent, action);
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

    /** 商品对外展示字段（多商品迭代后增加类别/库存/富文本/图片列表） */
    private Map<String, Object> publicProduct(Product p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("categoryId", p.getCategoryId());
        m.put("description", p.getDescription());
        m.put("richDescription", p.getRichDescription());
        m.put("price", p.getPrice());
        m.put("stock", p.getStock());
        m.put("imageUrl", p.getImage() == null ? null : "/uploads/" + p.getImage());
        m.put("imageUrls", productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(p.getId())
                .stream().map(pi -> "/uploads/" + pi.getFilename()).toList());
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

    /** 统一成功返回 {ok: true} */
    private Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }
}
