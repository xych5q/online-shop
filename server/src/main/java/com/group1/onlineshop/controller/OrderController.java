package com.group1.onlineshop.controller;

import com.group1.onlineshop.service.OrderService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * 订单控制器·买家侧（迭代 3 · W3-2/W3-4，挑战需求 B）。
 *
 * <pre>
 * POST /api/orders              从购物车勾选多件下单（O0，整单校验同事务扣减，US-26）
 * GET  /api/orders/my?page=     我的订单/历史（每页 10、下单时间倒序、已取消置灰，P5/G4）
 * POST /api/orders/{id}/cancel  买家取消（仅前三态，不填原因，US-22/G2）
 * </pre>
 *
 * <p>鉴权：买家 token 从请求头 {@code X-Token} 取（兼容 {@code Authorization: Bearer}），
 * 校验失败 401（与 BuyerController / FavoriteController 一致）。下单/取消均为账号功能（FR-D3）。
 */
@RestController
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    /** 下单：入参 {items:[{productId,quantity}]}（购物车勾选条目）；任一商品不足整单拦截 400。 */
    @PostMapping("/api/orders")
    public Map<String, Object> place(HttpServletRequest request,
                                     @RequestBody(required = false) Map<String, Object> body) {
        Object raw = body == null ? null : body.get("items");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = raw instanceof List<?> list ? (List<Map<String, Object>>) list : null;
        return orderService.placeOrder(token(request), items);
    }

    /** 我的订单/历史：?page=1 起，每页 10 条倒序。 */
    @GetMapping("/api/orders/my")
    public Map<String, Object> my(HttpServletRequest request,
                                  @RequestParam(defaultValue = "1") int page) {
        return orderService.myOrders(token(request), page);
    }

    /** 买家取消：仅 PLACED/CONFIRMED/PREPARED 可取消；开始发货后 400。 */
    @PostMapping("/api/orders/{id}/cancel")
    public Map<String, Object> cancel(HttpServletRequest request, @PathVariable long id) {
        return orderService.buyerCancel(token(request), id);
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
