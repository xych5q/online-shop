package com.group1.onlineshop.controller;

import com.group1.onlineshop.service.CartService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * 购物车控制器（迭代 3 · D3-2，买家侧，挑战需求 B）。
 *
 * <pre>
 * GET    /api/cart                          我的购物车（名称/单价/数量/小计，按账号，G12）
 * POST   /api/cart                          加购 {productId, quantity?}；重复加购合并累加（G10）
 * POST   /api/cart/{productId}/quantity     数量调整 {quantity}
 * DELETE /api/cart/{productId}              逐件移除（幂等）
 * POST   /api/cart/to-favorites             批量转收藏 {productIds:[..]}，转后移出购物车（FR-D11/G14/G15）
 * POST   /api/cart/validate                 结算前校验 {items?}，返回不可结算与缺货清单（G11）
 * </pre>
 *
 * <p>鉴权：买家 token 从请求头 {@code X-Token} 取（兼容 {@code Authorization: Bearer}），
 * 校验失败 401「未登录或登录已失效」（与 BuyerController / FavoriteController / OrderController 一致）。
 * 购物车属账号功能，未登录一律拒绝（FR-D3）。
 */
@RestController
@RequiredArgsConstructor
public class CartController {

    private final CartService cartService;

    /** 我的购物车：{ok,total,items[]}，只列名称/单价/数量/小计与状态标注（G12）。 */
    @GetMapping("/api/cart")
    public Map<String, Object> list(HttpServletRequest request) {
        return cartService.list(token(request));
    }

    /** 加购：入参 {productId, quantity}，quantity 缺省 1；同商品重复加购合并累加（G10）。 */
    @PostMapping("/api/cart")
    public Map<String, Object> add(HttpServletRequest request,
                                   @RequestBody(required = false) Map<String, Object> body) {
        Object value = body == null ? null : body.get("productId");
        Long productId = value instanceof Number number ? number.longValue() : null;
        Object quantityValue = body == null ? null : body.get("quantity");
        Integer quantity = quantityValue instanceof Number number ? number.intValue() : null;
        return cartService.add(token(request), productId, quantity);
    }

    /** 数量调整：覆盖式设置，必须大于 0；移除请走 DELETE 逐件移除。 */
    @PostMapping("/api/cart/{productId}/quantity")
    public Map<String, Object> updateQuantity(HttpServletRequest request, @PathVariable long productId,
                                              @RequestBody(required = false) Map<String, Object> body) {
        Object value = body == null ? null : body.get("quantity");
        Integer quantity = value instanceof Number number ? number.intValue() : null;
        return cartService.updateQuantity(token(request), productId, quantity);
    }

    /** 逐件移除：按商品 id 删除当前账号自己的条目（幂等）；不提供一键清空（SRS 1.2）。 */
    @DeleteMapping("/api/cart/{productId}")
    public Map<String, Object> remove(HttpServletRequest request, @PathVariable long productId) {
        return cartService.remove(token(request), productId);
    }

    /** 批量转收藏：入参 {productIds:[..]}；转后移出购物车，已收藏的静默去重（G14/G15）。 */
    @PostMapping("/api/cart/to-favorites")
    public Map<String, Object> toFavorites(HttpServletRequest request,
                                           @RequestBody(required = false) Map<String, Object> body) {
        Object raw = body == null ? null : body.get("productIds");
        List<Long> productIds = new ArrayList<>();
        if (raw instanceof List<?> list) {
            for (Object value : list) {
                if (value instanceof Number number) {
                    productIds.add(number.longValue());
                }
            }
        }
        return cartService.toFavorites(token(request), productIds);
    }

    /** 结算前校验：入参可选 {items:[{productId,quantity}]}，缺省校验整车；只读、不扣库存。 */
    @PostMapping("/api/cart/validate")
    public Map<String, Object> validate(HttpServletRequest request,
                                        @RequestBody(required = false) Map<String, Object> body) {
        Object raw = body == null ? null : body.get("items");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = raw instanceof List<?> list ? (List<Map<String, Object>>) list : null;
        return cartService.validate(token(request), items);
    }

    /** 从 X-Token 或 Authorization: Bearer 头取 token（与 BuyerController / SellerController 一致） */
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
