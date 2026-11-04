package com.group1.onlineshop.service;

import com.group1.onlineshop.entity.Buyer;
import com.group1.onlineshop.entity.Intent;
import com.group1.onlineshop.entity.Product;
import com.group1.onlineshop.entity.ProductImage;
import com.group1.onlineshop.entity.Seller;
import com.group1.onlineshop.exception.BizException;
import com.group1.onlineshop.repository.BuyerRepository;
import com.group1.onlineshop.repository.IntentRepository;
import com.group1.onlineshop.repository.ProductImageRepository;
import com.group1.onlineshop.repository.ProductRepository;
import com.group1.onlineshop.repository.SellerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 卖家端服务：登录鉴权、修改密码、工作台总览、历史商品与意向流水查询。
 * 业务规则逐条翻译自原型 prototype/single-seller-queue-trade/server.js 与 docs/design/接口设计.md，规则未做改动。
 */
@Service
@RequiredArgsConstructor
public class SellerService {

    private final SellerRepository sellerRepository;
    private final ProductRepository productRepository;
    private final IntentRepository intentRepository;
    private final ProductImageRepository productImageRepository;
    private final BuyerRepository buyerRepository;

    /** BCrypt 编码器：登录校验与修改密码后重新编码（BCrypt 自带随机盐） */
    private final BCryptPasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    /** 登录 token 随机源 */
    private final SecureRandom random = new SecureRandom();

    /** 历史列表每页条数（application.yml: app.history.page-size = 10） */
    @Value("${app.history.page-size:10}")
    private int historyPageSize;

    /**
     * 卖家登录。
     * 业务规则：用户名或密码错误统一返回 401「用户名或密码错误」（不区分是哪一项错）；
     * 成功后刷新 token 并持久化，旧 token 立即失效；返回 {token, username}。
     */
    public Map<String, Object> login(String username, String password) {
        String uname = username == null ? "" : username.trim();
        String pwd = password == null ? "" : password;
        Seller seller = sellerRepository.findByUsername(uname)
                .orElseThrow(() -> new BizException("用户名或密码错误", 401));
        if (!passwordEncoder.matches(pwd, seller.getPasswordHash())) {
            throw new BizException("用户名或密码错误", 401);
        }
        seller.setToken(newToken());
        sellerRepository.save(seller);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("token", seller.getToken());
        result.put("username", seller.getUsername());
        return result;
    }

    /**
     * 修改密码。
     * 业务规则：先验原密码，错误抛 400「原密码错误」；新密码长度 < 8 位抛 400「新密码长度不能少于 8 位」；
     * 成功后用 BCrypt 重新编码（每次编码自动换盐）并保存。
     */
    public Map<String, Object> changePassword(String token, String oldPassword, String newPassword) {
        Seller seller = requireAuth(token);
        String oldPwd = oldPassword == null ? "" : oldPassword;
        String newPwd = newPassword == null ? "" : newPassword;
        if (!passwordEncoder.matches(oldPwd, seller.getPasswordHash())) {
            throw new BizException("原密码错误");
        }
        if (newPwd.length() < 8) {
            throw new BizException("新密码长度不能少于 8 位");
        }
        seller.setPasswordHash(passwordEncoder.encode(newPwd));
        sellerRepository.save(seller);
        return ok();
    }

    /**
     * 鉴权：按 X-Token（或 Authorization: Bearer）携带的 token 查卖家。
     * 业务规则：token 为空、查不到均抛 401「未登录或登录已失效」；除 login 外所有 /api/seller/ 接口必须先鉴权。
     */
    public Seller requireAuth(String token) {
        if (token == null || token.isBlank()) {
            throw new BizException("未登录或登录已失效", 401);
        }
        return sellerRepository.findByToken(token.trim())
                .orElseThrow(() -> new BizException("未登录或登录已失效", 401));
    }

    /**
     * 工作台总览。
     * 业务规则：detail 为当前活跃（在售或冻结）商品及其队列/当前交易/待处置失败意向，无活跃商品时 detail 为 null；
     * queue 按提交时间正序并带 position（从 1 开始）；
     * currentDeal 与 pendingFailed 均不含口令码 code；
     * canPublish 为 true 表示当前无在售/交易中商品，或当前商品仅处于售罄态（多商品发布始终允许发布新品）。
     */
    public Map<String, Object> overview(String token) {
        requireAuth(token);
        // 售罄商品属于「当前商品」（评审意见）：仍显示在后台当前商品卡片，标「已售罄」并给补货入口（A.1.3-5）
        Product product = productRepository
                .findFirstByStatusInOrderByCreatedAtAsc(
                        List.of(Product.Status.ON_SALE, Product.Status.FROZEN, Product.Status.SOLD_OUT))
                .orElse(null);

        Map<String, Object> detail = null;
        if (product != null) {
            Intent currentDeal = intentRepository
                    .findByProductIdAndStatus(product.getId(), Intent.Status.IN_TRANSACTION).orElse(null);
            List<Intent> queue = intentRepository
                    .findByProductIdAndStatusOrderBySubmittedAtAscIdAsc(product.getId(), Intent.Status.QUEUED);
            List<Intent> pendingFailed = intentRepository
                    .findByProductIdAndStatusAndDisposedFalseOrderBySubmittedAtAsc(product.getId(), Intent.Status.FAILED);

            Map<String, Object> productJson = publicProduct(product);
            productJson.put("frozenBy", product.getFrozenBy());
            productJson.put("frozenAt", product.getFrozenAt());
            productJson.put("inTransaction", currentDeal != null);

            List<Map<String, Object>> queueJson = new ArrayList<>();
            for (int i = 0; i < queue.size(); i++) {
                Intent it = queue.get(i);
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", it.getId());
                m.put("position", i + 1);
                m.put("name", it.getName());
                m.put("phone", it.getPhone());
                m.put("submittedAt", it.getSubmittedAt());
                queueJson.add(m);
            }

            detail = new LinkedHashMap<>();
            detail.put("product", productJson);
            detail.put("queue", queueJson);
            detail.put("currentDeal", currentDeal == null ? null : intentSummary(currentDeal)); // 不含口令码
            List<Map<String, Object>> pendingFailedJson = new ArrayList<>();
            for (Intent it : pendingFailed) {
                pendingFailedJson.add(intentSummary(it)); // 不含口令码
            }
            detail.put("pendingFailed", pendingFailedJson);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("detail", detail);
        // 多商品发布（A.1.1-1）：发布新品始终被允许，canPublish 恒为 true（评审整改三）
        result.put("canPublish", true);
        return result;
    }

    /**
     * 商品列表（卖家后台管理主入口，评审整改一）。
     * 规则：需卖家鉴权；status 取值 all / on_sale / frozen / off_shelf / sold_out（默认 all），
     * 非法值 400「参数错误」；按创建时间倒序；page 从 1 起、每页 size 条（默认 10），越界取最后一页；
     * 每项含 {id,name,status,stock,price,categoryId,description,frozenBy,imageUrl,pendingCount,inTransaction,createdAt}，
     * 出参另有 {ok,page,pageSize,totalPages,total}——
     * pendingCount 为该商品排队中意向数，inTransaction 为该商品是否存在进行中交易；
     * frozenBy 仅在冻结态有值（auto 交易中 / manual 手动停售），其余为 null；
     * 售罄商品从 status=sold_out 筛进来管理（补货入口），历史接口保持「只收手动下架」语义不动。
     */
    public Map<String, Object> products(String token, String status, Integer page, Integer size) {
        requireAuth(token);
        Product.Status filter = parseStatus(status);
        int pageSize = size == null || size < 1 ? historyPageSize : Math.min(size, 100);
        long total = filter == null ? productRepository.count() : productRepository.countByStatus(filter);
        int totalPages = (int) Math.max(1, (total + pageSize - 1) / pageSize);
        int cur = Math.min(Math.max(1, page == null ? 1 : page), totalPages);
        // 双键排序（评审整改）：createdAt 倒序 + id 倒序兜底，同一秒创建的多件商品翻页不重不漏
        Pageable pageable = PageRequest.of(cur - 1, pageSize,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by(Sort.Direction.DESC, "id")));

        List<Map<String, Object>> items = new ArrayList<>();
        Page<Product> pageData = filter == null
                ? productRepository.findAll(pageable)
                : productRepository.findByStatus(filter, pageable);
        for (Product p : pageData) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("status", p.getStatus().name().toLowerCase());
            m.put("stock", p.getStock());
            m.put("price", p.getPrice());
            m.put("categoryId", p.getCategoryId());
            m.put("description", p.getDescription());
            m.put("frozenBy", p.getFrozenBy());
            m.put("imageUrl", mainImageUrl(p));
            m.put("pendingCount", intentRepository.countByProductIdAndStatus(p.getId(), Intent.Status.QUEUED));
            m.put("inTransaction", intentRepository
                    .findByProductIdAndStatus(p.getId(), Intent.Status.IN_TRANSACTION).isPresent());
            m.put("createdAt", p.getCreatedAt());
            items.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("page", cur);
        result.put("pageSize", pageSize);
        result.put("totalPages", totalPages);
        result.put("total", total);
        result.put("items", items);
        return result;
    }

    /** 主图 = sortOrder 最小的一张；无图时用旧单图字段 image；都没有则返回 null（前端显示占位图，与 CatalogService 同口径） */
    private String mainImageUrl(Product p) {
        return productImageRepository.findFirstByProductIdOrderBySortOrderAscIdAsc(p.getId())
                .map(ProductImage::getFilename)
                .or(() -> java.util.Optional.ofNullable(p.getImage()))
                .map(name -> "/uploads/" + name)
                .orElse(null);
    }

    /** 状态筛选参数解析：all → null（不过滤）；非法值抛 400「参数错误」 */
    private Product.Status parseStatus(String status) {
        if (status == null || status.isBlank() || "all".equalsIgnoreCase(status.trim())) {
            return null;
        }
        try {
            return Product.Status.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException("参数错误");
        }
    }

    /**
     * 历史商品列表（分页）。
     * 业务规则：只查已下架（OFF_SHELF）商品，按 closedAt 倒序，每页 10 条；
     * page 从 1 开始，越界时取最后一页；返回 {page, totalPages, total, items[]}，
     * items 含 intentCount（该商品全部意向数，含终态）。
     */
    public Map<String, Object> history(String token, Integer page) {
        requireAuth(token);
        long total = productRepository.countByStatus(Product.Status.OFF_SHELF);
        int totalPages = (int) Math.max(1, (total + historyPageSize - 1) / historyPageSize);
        int cur = Math.min(Math.max(1, page == null ? 1 : page), totalPages);
        Pageable pageable = PageRequest.of(cur - 1, historyPageSize, Sort.by(Sort.Direction.DESC, "closedAt"));

        List<Map<String, Object>> items = new ArrayList<>();
        for (Product p : productRepository.findByStatus(Product.Status.OFF_SHELF, pageable)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", p.getId());
            m.put("name", p.getName());
            m.put("price", p.getPrice());
            m.put("imageUrl", p.getImage() == null ? null : "/uploads/" + p.getImage());
            m.put("publishedAt", p.getCreatedAt());
            m.put("closedAt", p.getClosedAt());
            m.put("intentCount", intentRepository.findByProductIdOrderBySubmittedAtAsc(p.getId()).size());
            items.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("page", cur);
        result.put("totalPages", totalPages);
        result.put("total", total);
        result.put("items", items);
        return result;
    }

    /**
     * 历史商品详情与意向流水。
     * 业务规则：只允许查看已下架（OFF_SHELF）商品，否则 404「历史商品不存在」；
     * 意向流水按提交时间正序返回，绝对不含口令码 code；只读，无修改/删除。
     */
    public Map<String, Object> historyDetail(String token, long id) {
        requireAuth(token);
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new BizException("历史商品不存在", 404));
        if (product.getStatus() != Product.Status.OFF_SHELF) {
            throw new BizException("历史商品不存在", 404);
        }

        Map<String, Object> productJson = new LinkedHashMap<>();
        productJson.put("id", product.getId());
        productJson.put("name", product.getName());
        productJson.put("description", product.getDescription());
        productJson.put("price", product.getPrice());
        productJson.put("imageUrl", product.getImage() == null ? null : "/uploads/" + product.getImage());
        productJson.put("publishedAt", product.getCreatedAt());
        productJson.put("closedAt", product.getClosedAt());

        List<Map<String, Object>> intents = new ArrayList<>();
        for (Intent it : intentRepository.findByProductIdOrderBySubmittedAtAsc(product.getId())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", it.getName());
            m.put("phone", it.getPhone());
            m.put("submittedAt", it.getSubmittedAt());
            m.put("status", it.getStatus().name().toLowerCase());
            intents.add(m); // 口令码不进历史
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("product", productJson);
        result.put("intents", intents);
        return result;
    }

    /**
     * 客户列表（卖家后台）。
     * 规则：需卖家鉴权；返回全部已注册买家，按注册时间倒序；
     * 只返回 {id,username,phone,defaultLocation,createdAt}——绝不返回 passwordHash 与登录 token（隐私）。
     */
    public Map<String, Object> customers(String token) {
        requireAuth(token);
        List<Map<String, Object>> items = new ArrayList<>();
        for (Buyer b : buyerRepository.findAllByOrderByCreatedAtDesc(Pageable.unpaged())) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", b.getId());
            m.put("username", b.getUsername());
            m.put("phone", b.getPhone());
            m.put("defaultLocation", b.getDefaultLocation());
            m.put("createdAt", b.getCreatedAt());
            items.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("total", items.size());
        result.put("items", items);
        return result;
    }

    /**
     * 购买历史 · 维度一：按客户看。
     * 规则：需卖家鉴权；客户不存在 404「客户不存在」；返回该买家的全部意向历史
     * （排除归档 ARCHIVED），按提交时间倒序、每页 10 条、page 从 1 开始；
     * 每条含 {id,productId,productName,status,submittedAt,dealtAt}，绝不包含口令码。
     */
    public Map<String, Object> customerHistory(String token, long buyerId, Integer page) {
        requireAuth(token);
        if (!buyerRepository.existsById(buyerId)) {
            throw new BizException("客户不存在", 404);
        }
        long total = intentRepository.countByBuyerIdAndStatusNot(buyerId, Intent.Status.ARCHIVED);
        int totalPages = (int) Math.max(1, (total + historyPageSize - 1) / historyPageSize);
        int cur = Math.min(Math.max(1, page == null ? 1 : page), totalPages);
        Pageable pageable = PageRequest.of(cur - 1, historyPageSize);

        List<Map<String, Object>> items = new ArrayList<>();
        for (Intent it : intentRepository.findByBuyerIdAndStatusNotOrderBySubmittedAtDescIdDesc(
                buyerId, Intent.Status.ARCHIVED, pageable)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", it.getId());
            m.put("productId", it.getProductId());
            m.put("productName", productRepository.findById(it.getProductId())
                    .map(Product::getName).orElse(null));
            m.put("status", it.getStatus().name().toLowerCase());
            m.put("submittedAt", it.getSubmittedAt());
            m.put("dealtAt", it.getDealtAt());
            items.add(m);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("page", cur);
        result.put("totalPages", totalPages);
        result.put("total", total);
        result.put("items", items);
        return result;
    }

    /**
     * 购买历史 · 维度二：按商品看意向购买人。
     * 规则：需卖家鉴权；商品不存在 404「商品不存在」；返回该商品的全部意向（含已下架商品的历史意向），
     * 按提交时间正序；每条含 {id,buyerId,username,phone,status,position,submittedAt,dealtAt}——
     * position 仅对排队中（QUEUED）意向计算（从 1 起）；绝不包含口令码。
     */
    public Map<String, Object> productIntents(String token, long productId) {
        requireAuth(token);
        if (!productRepository.existsById(productId)) {
            throw new BizException("商品不存在", 404);
        }
        List<Map<String, Object>> items = new ArrayList<>();
        int queued = 0;
        for (Intent it : intentRepository.findByProductIdOrderBySubmittedAtAsc(productId)) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", it.getId());
            m.put("buyerId", it.getBuyerId());
            Buyer buyer = it.getBuyerId() == null ? null : buyerRepository.findById(it.getBuyerId()).orElse(null);
            m.put("username", buyer == null ? null : buyer.getUsername());
            m.put("phone", it.getPhone() == null ? (buyer == null ? null : buyer.getPhone()) : it.getPhone());
            m.put("status", it.getStatus().name().toLowerCase());
            // 处置标记（评审整改）：前端用它区分「待处置」与「已作废」的失败意向
            m.put("disposed", it.isDisposed());
            if (it.getStatus() == Intent.Status.QUEUED) {
                queued++;
                m.put("position", queued);
            } else {
                m.put("position", null);
            }
            m.put("submittedAt", it.getSubmittedAt());
            m.put("dealtAt", it.getDealtAt());
            items.add(m);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("total", items.size());
        result.put("items", items);
        return result;
    }

    /** 生成 48 位十六进制随机 token（对应原型的 24 字节随机数转 hex） */
    private String newToken() {
        byte[] bytes = new byte[24];
        random.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /** 意向摘要（卖家侧对外输出，不含口令码） */
    private Map<String, Object> intentSummary(Intent it) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", it.getId());
        m.put("name", it.getName());
        m.put("phone", it.getPhone());
        m.put("submittedAt", it.getSubmittedAt());
        return m;
    }

    /** 商品对外展示字段（与买家侧 /api/product 保持一致，多商品迭代后增加类别/库存/富文本/图片列表） */
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

    /** 统一成功返回 {ok: true} */
    private Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }
}
