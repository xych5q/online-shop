package com.group1.onlineshop.service;

import com.group1.onlineshop.entity.Intent;
import com.group1.onlineshop.entity.Product;
import com.group1.onlineshop.exception.BizException;
import com.group1.onlineshop.repository.IntentRepository;
import com.group1.onlineshop.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 买家端服务：提交意向、口令码查询 / 修改 / 撤销、当前商品查询。
 * 业务规则逐条翻译自原型 prototype/single-seller-queue-trade/server.js（第 330~395 行买家接口）
 * 与 docs/design/接口设计.md，规则未做改动。
 */
@Service
@RequiredArgsConstructor
public class IntentService {

    /** 口令码字符集：8 位大写字母数字，剔除易混淆的 I / O / 0 / 1 */
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    /** 口令码长度 */
    private static final int CODE_LENGTH = 8;

    /** 口令码随机源 */
    private static final SecureRandom RANDOM = new SecureRandom();

    /** 生成尝试上限（32^8 的码空间下碰撞概率可忽略，上限仅为保险，与原型一致） */
    private static final int CODE_MAX_ATTEMPTS = 100;

    private final IntentRepository intentRepository;
    private final ProductRepository productRepository;

    /**
     * 提交购买意向。
     * 业务规则：无在售或冻结商品 → 400「暂无商品在售」；
     * 商品状态非在售 → 400「商品交易中，暂停接收新意向」；
     * 姓名与电话去空格后任一为空 → 400「姓名与联系电话均为必填」；
     * 生成全库唯一的 8 位口令码，创建排队中意向并返回口令码。
     * 口令码只在此响应中返回一次，任何其他接口都不再返回。
     *
     * @param name  买家姓名
     * @param phone 联系电话
     * @return 8 位口令码
     */
    @Transactional
    public String submit(String name, String phone) {
        Product product = productRepository
                .findFirstByStatusInOrderByCreatedAtAsc(List.of(Product.Status.ON_SALE, Product.Status.FROZEN))
                .orElseThrow(() -> new BizException("暂无商品在售"));
        if (product.getStatus() != Product.Status.ON_SALE) {
            throw new BizException("商品交易中，暂停接收新意向");
        }
        String n = name == null ? "" : name.trim();
        String p = phone == null ? "" : phone.trim();
        if (n.isEmpty() || p.isEmpty()) {
            throw new BizException("姓名与联系电话均为必填");
        }
        Intent intent = new Intent();
        intent.setProductId(product.getId());
        intent.setName(n);
        intent.setPhone(p);
        intent.setCode(generateUniqueCode());
        intent.setSubmittedAt(LocalDateTime.now());
        intent.setStatus(Intent.Status.QUEUED);
        intent.setDisposed(false);
        intentRepository.save(intent);
        return intent.getCode();
    }

    /**
     * 凭口令码查询意向与位次。
     * 业务规则：口令码不存在或对应意向已处终态（成功 / 撤销 / 失败）→ 404「口令码无效或已失效」；
     * 已进入交易时 position 为 null、inTransaction 为 true；
     * 返回 {intent:{name,phone,status,inTransaction,position,submittedAt}, product}，
     * product 为商品公开信息（商品不存在时为 null），绝不包含口令码。
     *
     * @param code 口令码
     * @return 意向与商品信息
     */
    @Transactional(readOnly = true)
    public Map<String, Object> lookup(String code) {
        Intent intent = findActiveByCode(code);
        Product product = productRepository.findById(intent.getProductId()).orElse(null);
        List<Intent> queue = intentRepository
                .findByProductIdAndStatusOrderBySubmittedAtAscIdAsc(intent.getProductId(), Intent.Status.QUEUED);
        int position = 0;
        for (int i = 0; i < queue.size(); i++) {
            if (queue.get(i).getId().equals(intent.getId())) {
                position = i + 1;
                break;
            }
        }
        boolean inTransaction = intent.getStatus() == Intent.Status.IN_TRANSACTION;

        Map<String, Object> intentJson = new LinkedHashMap<>();
        intentJson.put("name", intent.getName());
        intentJson.put("phone", intent.getPhone());
        intentJson.put("status", intent.getStatus().name().toLowerCase());
        intentJson.put("inTransaction", inTransaction);
        intentJson.put("position", inTransaction ? null : position);
        intentJson.put("submittedAt", intent.getSubmittedAt());

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("intent", intentJson);
        result.put("product", product == null ? null : publicProduct(product));
        return result;
    }

    /**
     * 修改意向的姓名与电话。
     * 业务规则：口令码无效或已失效 → 404；姓名与电话去空格后任一为空 → 400「姓名与联系电话均为必填」；
     * 只更新姓名和电话，不修改 submittedAt，因此不改变排队位次。
     *
     * @param code  口令码
     * @param name  新姓名
     * @param phone 新电话
     */
    @Transactional
    public void update(String code, String name, String phone) {
        Intent intent = findActiveByCode(code);
        String n = name == null ? "" : name.trim();
        String p = phone == null ? "" : phone.trim();
        if (n.isEmpty() || p.isEmpty()) {
            throw new BizException("姓名与联系电话均为必填");
        }
        intent.setName(n);
        intent.setPhone(p);
        intentRepository.save(intent);
    }

    /**
     * 撤销意向。
     * 业务规则：口令码无效或已失效 → 404；
     * 已进入交易 → 400「你已进入交易，无法自行撤销，请联系卖家」；
     * 其余状态置为已撤销（终态，历史留痕）。
     *
     * @param code 口令码
     */
    @Transactional
    public void cancel(String code) {
        Intent intent = findActiveByCode(code);
        if (intent.getStatus() == Intent.Status.IN_TRANSACTION) {
            throw new BizException("你已进入交易，无法自行撤销，请联系卖家");
        }
        intent.setStatus(Intent.Status.CANCELLED);
        intentRepository.save(intent);
    }

    /**
     * 买家侧当前商品。
     * 业务规则：只返回在售或已冻结的商品，无则返回 null（前端显示「暂无商品在售」）；
     * 仅返回商品公开信息，绝不包含意向或口令码信息。
     *
     * @return 商品公开信息，无商品时为 null
     */
    @Transactional(readOnly = true)
    public Map<String, Object> activeProduct() {
        return productRepository
                .findFirstByStatusInOrderByCreatedAtAsc(List.of(Product.Status.ON_SALE, Product.Status.FROZEN))
                .map(this::publicProduct)
                .orElse(null);
    }

    /** 凭口令码找到有效（未终态）的意向：口令码去空格转大写后查询，不存在或已处终态 → 404（与原型 findActiveByCode 一致） */
    private Intent findActiveByCode(String code) {
        String c = code == null ? "" : code.trim().toUpperCase();
        Intent intent = intentRepository.findByCode(c)
                .orElseThrow(() -> new BizException("口令码无效或已失效", 404));
        if (intent.getStatus() == Intent.Status.SUCCESS
                || intent.getStatus() == Intent.Status.CANCELLED
                || intent.getStatus() == Intent.Status.FAILED) {
            throw new BizException("口令码无效或已失效", 404);
        }
        return intent;
    }

    /** 生成全库唯一的 8 位口令码：从固定字符集随机取值，撞码则重新生成（与原型 genCode 一致） */
    private String generateUniqueCode() {
        for (int attempt = 0; attempt < CODE_MAX_ATTEMPTS; attempt++) {
            StringBuilder sb = new StringBuilder(CODE_LENGTH);
            for (int i = 0; i < CODE_LENGTH; i++) {
                sb.append(CODE_ALPHABET.charAt(RANDOM.nextInt(CODE_ALPHABET.length())));
            }
            String code = sb.toString();
            if (intentRepository.findByCode(code).isEmpty()) {
                return code;
            }
        }
        throw new BizException("口令码生成失败，请重试", 500);
    }

    /** 商品对外展示字段（与原型 publicProduct、卖家侧 /api/product 保持一致） */
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
}
