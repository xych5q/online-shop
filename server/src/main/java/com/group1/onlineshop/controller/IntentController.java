package com.group1.onlineshop.controller;

import com.group1.onlineshop.entity.Buyer;
import com.group1.onlineshop.service.BuyerService;
import com.group1.onlineshop.service.IntentService;
import com.group1.onlineshop.service.SellerService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 意向与队列控制器（队列与交易域；owner 王振涛，任务 W-3 / W-4）。
 *
 * <p>本文件为迭代 2 新建，承载：
 * <pre>
 * POST /api/intents                      提交购买意向（<b>买家登录态</b>，入参 {productId}）
 * GET  /api/seller/products/{id}/queue   某商品队列明细（卖家登录态，10 条/页）
 * </pre>
 *
 * <p><b>为什么单独建这个控制器</b>：{@code POST /api/intents} 原在 {@code BuyerController}（董嘉润独占），
 * 迭代 2 改为登录态 + 绑账号后属队列与交易域；按 CODEOWNERS 文件独占纪律，
 * 端点迁入本文件，原文件只做删除，避免两处重复映射（Spring 会因 Ambiguous mapping 启动失败）。
 *
 * <p><b>端点归属说明</b>：卖家侧的 {@code /api/seller/products/{id}/deal/result} 与
 * {@code /api/seller/intents/{id}/dispose} 两个端点仍留在 {@code SellerController}（林初俊文件），
 * 其业务逻辑已全部委托 {@code TradeService}（本域）。端点在那里、逻辑在这里，不重复实现。
 */
@RestController
@RequiredArgsConstructor
public class IntentController {

    private final BuyerService buyerService;
    private final SellerService sellerService;
    private final IntentService intentService;

    /**
     * 提交购买意向（FR-D3：必须登录态，不提供匿名通道）。
     *
     * <p>入参 {productId}；身份与联系电话取自登录账号（{@code buyerId} + 账号电话快照）。
     * 出参 {intent:{intentId,productId,status,submittedAt}}，<b>不返回口令码、不返回排队位次</b>。
     */
    @PostMapping("/api/intents")
    public Map<String, Object> submit(@RequestBody Map<String, Object> body, HttpServletRequest request) {
        Buyer buyer = buyerService.requireAuth(token(request));
        return intentService.submit(buyer, longValue(body == null ? null : body.get("productId")));
    }

    /**
     * 某商品的队列明细（接口设计 v2 · T-2）。
     * 出参 {product,queue[],currentDeal,pendingFailed[],page,totalPages,total}；{@code ?page=n} 从 1 起。
     */
    @GetMapping("/api/seller/products/{id}/queue")
    public Map<String, Object> queue(@PathVariable long id, HttpServletRequest request,
                                      @RequestParam(required = false) Integer page) {
        sellerService.requireAuth(token(request));
        return intentService.queue(id, page);
    }

    /** 从 X-Token 或 Authorization: Bearer 头取 token（与 BuyerController / SellerController 保持一致） */
    private String token(HttpServletRequest request) {
        String t = request.getHeader("X-Token");
        if (t == null || t.isBlank()) {
            String auth = request.getHeader("Authorization");
            if (auth != null && auth.length() > 7 && auth.substring(0, 7).equalsIgnoreCase("Bearer ")) {
                t = auth.substring(7);
            }
        }
        return t == null ? "" : t.trim();
    }

    /** 数值转换：兼容 JSON 数字与字符串；null 或非法值返回 null（由服务层给出 400） */
    private Long longValue(Object o) {
        if (o == null) {
            return null;
        }
        if (o instanceof Number n) {
            return n.longValue();
        }
        String s = String.valueOf(o).trim();
        if (s.isEmpty()) {
            return null;
        }
        try {
            return Long.valueOf(s);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
