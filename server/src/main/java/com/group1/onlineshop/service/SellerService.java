package com.group1.onlineshop.service;

import com.group1.onlineshop.entity.Intent;
import com.group1.onlineshop.entity.Product;
import com.group1.onlineshop.entity.Seller;
import com.group1.onlineshop.exception.BizException;
import com.group1.onlineshop.repository.IntentRepository;
import com.group1.onlineshop.repository.ProductRepository;
import com.group1.onlineshop.repository.SellerRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
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
     * canPublish 为 true 表示当前无在售/交易中商品（可发布新品）。
     */
    public Map<String, Object> overview(String token) {
        requireAuth(token);
        Product product = productRepository
                .findFirstByStatusInOrderByCreatedAtAsc(List.of(Product.Status.ON_SALE, Product.Status.FROZEN))
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
        result.put("canPublish", detail == null);
        return result;
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

    /** 商品对外展示字段（与买家侧 /api/product 保持一致） */
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

    /** 统一成功返回 {ok: true} */
    private Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }
}
