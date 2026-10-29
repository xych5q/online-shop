package com.group1.onlineshop.service;

import com.group1.onlineshop.entity.Buyer;
import com.group1.onlineshop.entity.Intent;
import com.group1.onlineshop.entity.Product;
import com.group1.onlineshop.exception.BizException;
import com.group1.onlineshop.repository.BuyerRepository;
import com.group1.onlineshop.repository.IntentRepository;
import com.group1.onlineshop.repository.ProductImageRepository;
import com.group1.onlineshop.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 意向服务（队列与交易域；文件独占 owner 王振涛，见 .github/CODEOWNERS）。
 *
 * <p>迭代 2（挑战需求 A）口径：
 * <ul>
 *   <li><b>W-3 意向改绑账号</b>：提交意向必须登录态；{@code buyerId} 记当前账号，
 *       {@code phone} 记账号电话<b>快照</b>（提交瞬间的电话，后续改账号不回改历史流水）；
 *       {@code name} 停止写入（注册无姓名字段，A.2.1-10）；{@code code} 恒为 {@code null}（C6 口令码废弃）</li>
 *   <li><b>FR-D7 / A.2.3-8</b>：同一账号对同一商品可重复提交，<b>无去重、无限次</b>，每条独立排队</li>
 *   <li><b>FR-C3 / A.1.3-4</b>：库存<b>不限制排队人数</b>，库存为 0 才拒绝新意向</li>
 *   <li><b>FR-C5 / A.1.3-2</b>：提交意向<b>不扣库存</b></li>
 *   <li><b>A.2.2-6</b>：不返回排队位次（位次查询能力废弃且无替代入口）</li>
 *   <li><b>已删除</b>：凭口令码的查询 / 改名 / 撤销三个接口（A.2.2-6 / A.2.4-2 / A.2.2-9）</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class IntentService {

    private final IntentRepository intentRepository;
    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final BuyerRepository buyerRepository;
    private final TradeService tradeService;

    /** 队列明细每页条数（接口设计 v2 §1.2：商品列表 12 条、其余 10 条） */
    private static final int QUEUE_PAGE_SIZE = 10;

    /** 我的历史每页条数（FR-D4 / A.2.2-3：10 条/页） */
    private static final int HISTORY_PAGE_SIZE = 10;

    /**
     * 提交购买意向（<b>登录态</b>，W-3）。
     *
     * <p>入参由控制器给出：{@code buyer} 为 {@code BuyerService.requireAuth(token)} 解析出的当前账号，
     * {@code productId} 为目标商品（多商品场景必须指定，FR-C1）。
     *
     * <p>规则：商品不存在 → 400「商品不存在」；已下架 / 售罄 → 400「商品已下架」；
     * 非在售（冻结 / 交易中）→ 400「商品交易中，暂停接收新意向」；库存为 0 → 400「商品已售罄」。
     * 提交成功后，若该商品当前无进行中的交易，则由 {@link TradeService#enterDeal(Product)} 让队首自动进入交易（W-4）。
     *
     * @param buyer     当前登录买家（登录态由控制器保证）
     * @param productId 目标商品 id
     * @return {intent:{intentId,productId,status,submittedAt}}，<b>不含 code、不返回位次</b>
     */
    @Transactional
    public Map<String, Object> submit(Buyer buyer, Long productId) {
        if (productId == null) {
            throw new BizException("请选择要购买的商品");
        }
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BizException("商品不存在"));
        if (product.getStatus() == Product.Status.OFF_SHELF || product.getStatus() == Product.Status.SOLD_OUT) {
            throw new BizException("商品已下架");
        }
        if (product.getStatus() != Product.Status.ON_SALE) {
            throw new BizException("商品交易中，暂停接收新意向");
        }
        if (product.getStock() == null || product.getStock() < 1) {
            throw new BizException("商品已售罄");
        }

        Intent intent = new Intent();
        intent.setProductId(product.getId());
        intent.setBuyerId(buyer.getId());
        intent.setPhone(buyer.getPhone()); // 账号电话快照
        intent.setName(null);              // 无姓名字段（A.2.1-10）
        intent.setCode(null);              // 口令码整体废弃（C6）
        intent.setSubmittedAt(LocalDateTime.now());
        intent.setStatus(Intent.Status.QUEUED);
        intent.setDisposed(false);
        intentRepository.save(intent);

        // ⚠️ W-4 挂起（2026-10-21）：「队首提交后立即自动进入交易」的触发时机存在需求冲突，待组长 / 需求方裁定后再补。
        // 冲突：SRS 4.1 触发条件「在售且有排队意向 → 队首自动进交易」+「冻结期间不接收新意向」+ Q6 裁定「队列必须能形成」三者不可兼得
        //      ——若提交即进交易并冻结，第二位之后买家被拒，队列最多 1 人，「排队是核心功能」失效。
        // 挂起期间沿用迭代 1 现状：提交只入队，由卖家在后台触发首次进入交易；交易结束后由系统自动推进下一位（Q7 裁定，无争议）。
        // 裁定后在此补：if (!tradeService.hasActiveDeal(product.getId())) { tradeService.enterDeal(product); }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("intent", intentJson(intent));
        return result;
    }

    /**
     * 某商品的队列明细（接口设计 v2 · T-2，供卖家后台按商品查看，L-2）。
     *
     * <p>出参 {product, queue[], currentDeal, pendingFailed[], page, totalPages, total}；
     * {@code queue} 按 {@code submittedAt} 升序并带 {@code position}（从 1 起）；
     * 展示的是买家<b>用户名</b>（无独立姓名字段）；<b>不含 code</b>。
     *
     * @param page 页码，从 1 起
     */
    @Transactional(readOnly = true)
    public Map<String, Object> queue(Long productId, Integer page) {
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new BizException("商品不存在", 404));

        List<Intent> all = intentRepository
                .findByProductIdAndStatusOrderBySubmittedAtAscIdAsc(productId, Intent.Status.QUEUED);
        int cur = page == null || page < 1 ? 1 : page;
        int totalPages = Math.max(1, (all.size() + QUEUE_PAGE_SIZE - 1) / QUEUE_PAGE_SIZE);
        int from = Math.min((cur - 1) * QUEUE_PAGE_SIZE, Math.max(0, all.size() - 1));
        int to = Math.min(from + QUEUE_PAGE_SIZE, all.size());
        List<Intent> slice = all.isEmpty() ? List.of() : all.subList(from, to);

        List<Map<String, Object>> queue = new ArrayList<>();
        for (int i = from; i < to; i++) {
            queue.add(queueItem(all.get(i), i + 1, false));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("product", publicProduct(product));
        result.put("queue", queue);
        result.put("currentDeal", intentRepository
                .findByProductIdAndStatus(productId, Intent.Status.IN_TRANSACTION)
                .map(it -> queueItem(it, null, false)).orElse(null));
        result.put("pendingFailed", intentRepository
                .findByProductIdAndStatusAndDisposedFalseOrderBySubmittedAtAsc(productId, Intent.Status.FAILED)
                .stream().map(it -> queueItem(it, null, true)).toList());
        result.put("page", cur);
        result.put("totalPages", totalPages);
        result.put("total", all.size());
        return result;
    }

    /**
     * 我的意向分页（<b>买家历史三值展示的数据源</b>，接口设计 v2 §2.5 服务层契约，调用方董嘉润 D-4）。
     *
     * <p>口径（FR-D4 / A.2.2-1 ~ A.2.2-8）：
     * <ul>
     *   <li>只取该账号名下的意向（{@code buyerId}）；旧系统归档数据 {@code buyerId} 为 null，自然不出现（A.2.4-3 B 方案）</li>
     *   <li>按 {@code submittedAt} <b>倒序</b>，同一时刻以 {@code id} 倒序兜底</li>
     *   <li>{@code ARCHIVED}（旧系统遗留只读数据）<b>不展示</b></li>
     *   <li>{@code dealTime} 取 {@code Intent.dealtAt}：由 {@link TradeService#markResult} 在成功与失败时写入（A.2.2-2），
     *       <b>不</b>取商品 {@code closedAt}——成交不再等于下架，商品未下架时该字段为 null</li>
     *   <li>商品已被删除时 {@code productName} 为空串，不整条报错</li>
     * </ul>
     *
     * @param buyerId 当前登录买家 id
     * @param page    页码，从 1 起
     * @return {@code {page,totalPages,total,items:[{intentId,productId,productName,submittedAt,result,dealTime}]}}
     */
    @Transactional(readOnly = true)
    public Map<String, Object> listMyIntents(Long buyerId, Integer page) {
        int cur = page == null || page < 1 ? 1 : page;
        Page<Intent> p = intentRepository.findByBuyerIdAndStatusNotOrderBySubmittedAtDescIdDesc(
                buyerId, Intent.Status.ARCHIVED, PageRequest.of(cur - 1, HISTORY_PAGE_SIZE));

        List<Map<String, Object>> items = new ArrayList<>();
        for (Intent it : p.getContent()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("intentId", it.getId());
            m.put("productId", it.getProductId());
            m.put("productName", productNameOf(it.getProductId()));
            m.put("submittedAt", it.getSubmittedAt());
            m.put("result", resultText(it.getStatus()));
            m.put("dealTime", it.getDealtAt());
            items.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("page", cur);
        result.put("totalPages", p.getTotalPages());
        result.put("total", p.getTotalElements());
        result.put("items", items);
        return result;
    }

    /**
     * 意向状态 → 买家侧三值（接口设计 v2 §3.5）：
     * {@code QUEUED} / {@code IN_TRANSACTION} → 待交易；{@code SUCCESS} → 成功；
     * {@code FAILED} / {@code SOLD_OUT}（售罄失效）/ {@code CANCELLED}（旧数据）→ 失败。
     */
    private static String resultText(Intent.Status status) {
        if (status == Intent.Status.SUCCESS) {
            return "成功";
        }
        if (status == Intent.Status.QUEUED || status == Intent.Status.IN_TRANSACTION) {
            return "待交易";
        }
        return "失败";
    }

    /** 商品名：商品已被删除时返回空串，不整条报错（A.2.2-6） */
    private String productNameOf(Long productId) {
        return productRepository.findById(productId).map(Product::getName).orElse("");
    }

    /**
     * 买家侧当前商品（<b>基线遗留，过渡用</b>）。
     *
     * <p>迭代 2 已判定废弃：多商品场景由 {@code GET /api/products} 与 {@code GET /api/products/{id}}
     * 承接（嵇宇锋 J-1）。因 {@code GET /api/product} 在董嘉润的 BuyerController 中仍在调用本方法，
     * 故暂保留；待前端切换完成后由董嘉润删端点、我再删本方法。
     */
    @Transactional(readOnly = true)
    public Map<String, Object> activeProduct() {
        return productRepository
                .findFirstByStatusInOrderByCreatedAtAsc(List.of(Product.Status.ON_SALE, Product.Status.FROZEN))
                .map(this::publicProduct)
                .orElse(null);
    }

    /** 意向对外字段：不含 code（口令码已废弃）、不含内部处置标记 */
    private Map<String, Object> intentJson(Intent intent) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("intentId", intent.getId());
        m.put("productId", intent.getProductId());
        m.put("status", intent.getStatus().name().toLowerCase());
        m.put("submittedAt", intent.getSubmittedAt());
        return m;
    }

    /** 卖家侧队列条目：position 为排队位次（当前交易/待处置传 null），showDisposed 时附带是否待处置 */
    private Map<String, Object> queueItem(Intent intent, Integer position, boolean showDisposed) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("intentId", intent.getId());
        m.put("position", position);
        m.put("buyerId", intent.getBuyerId());
        m.put("buyerName", buyerName(intent.getBuyerId()));
        m.put("phone", intent.getPhone());
        m.put("status", intent.getStatus().name().toLowerCase());
        m.put("submittedAt", intent.getSubmittedAt());
        if (showDisposed) {
            m.put("disposed", intent.isDisposed());
        }
        return m;
    }

    /** 买家用户名；账号不存在或旧数据无账号时给空串 */
    private String buyerName(Long buyerId) {
        if (buyerId == null) {
            return "";
        }
        return buyerRepository.findById(buyerId).map(Buyer::getUsername).orElse("");
    }

    /** 商品对外展示字段（多商品：类别 / 库存 / 富文本 / 多图） */
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
}
