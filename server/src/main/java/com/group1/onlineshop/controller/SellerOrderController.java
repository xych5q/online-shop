package com.group1.onlineshop.controller;

import com.group1.onlineshop.service.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 订单控制器·商家侧（迭代 3 · W3-3，挑战需求 B；独立于既有 SellerController 以免交叉改动）。
 *
 * <pre>
 * GET  /api/seller/orders?page=&status=   商家订单列表（每页 10、倒序，P5；可按状态筛选）
 * POST /api/seller/orders/{id}/advance    五状态单向逐级推进（不可跳级不可回退，US-21）
 * POST /api/seller/orders/{id}/cancel     商家单笔取消（交易完成前均可，含开始发货，US-23）
 * POST /api/seller/orders/batch-cancel    商家批量取消（单接口单事务，全部可取消才执行，G5/US-23）
 * </pre>
 *
 * <p>鉴权：卖家 token 从请求头 {@code X-Token} 取（兼容 {@code Authorization: Bearer}），
 * 校验失败 401（与 SellerController 一致）。
 */
@RestController
@RequiredArgsConstructor
public class SellerOrderController {

    private final OrderService orderService;

    /** 商家订单列表：?page=1 起、?status= 可选（6 枚举大写）。 */
    @GetMapping("/api/seller/orders")
    public Map<String, Object> list(HttpServletRequest request,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(required = false) String status) {
        return orderService.sellerOrders(token(request), page, status);
    }

    /** 推进：按当前状态推下一级，PLACED→CONFIRMED→PREPARED→SHIPPING→COMPLETED；终态拒绝。 */
    @PostMapping("/api/seller/orders/{id}/advance")
    public Map<String, Object> advance(HttpServletRequest request, @PathVariable long id) {
        return orderService.advance(token(request), id);
    }

    /** 商家单笔取消：前四态可取消（含开始发货）；COMPLETED/CANCELLED 拒绝。 */
    @PostMapping("/api/seller/orders/{id}/cancel")
    public Map<String, Object> cancel(HttpServletRequest request, @PathVariable long id) {
        return orderService.sellerCancel(token(request), id);
    }

    /**
     * 批量取消：入参 {orderIds:[...]}。全部可取消 → 同一事务整批执行 200；
     * 任一不可取消 → 整批不执行，400 + 失败清单 [{orderId,reason}]（G5 口径 #2）。
     */
    @PostMapping("/api/seller/orders/batch-cancel")
    public ResponseEntity<Map<String, Object>> batchCancel(HttpServletRequest request,
                                                           @RequestBody(required = false) Map<String, Object> body) {
        Object raw = body == null ? null : body.get("orderIds");
        @SuppressWarnings("unchecked")
        List<Long> orderIds = raw instanceof List<?> list ? list.stream()
                .filter(v -> v instanceof Number).map(v -> ((Number) v).longValue()).toList() : null;
        Map<String, Object> result = orderService.batchCancel(token(request), orderIds);
        boolean ok = Boolean.TRUE.equals(result.get("ok"));
        return ResponseEntity.status(ok ? 200 : 400).body(result);
    }

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
}
