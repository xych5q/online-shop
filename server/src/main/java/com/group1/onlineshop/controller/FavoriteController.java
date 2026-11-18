package com.group1.onlineshop.controller;

import com.group1.onlineshop.service.FavoriteService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 收藏控制器（迭代 3 · L3-5，买家侧，任务边界见工作手册）。
 *
 * <pre>
 * GET    /api/favorites            我的收藏列表（按账号，独立列表；已下架/售罄保留并标注）
 * POST   /api/favorites            收藏商品，入参 {productId}；重复收藏去重不报错（G15）
 * DELETE /api/favorites/{productId} 取消收藏（幂等）
 * </pre>
 *
 * <p>鉴权：买家 token 从请求头 {@code X-Token} 取（兼容 {@code Authorization: Bearer}），
 * 校验失败 401「未登录或登录已失效」（与 BuyerController / IntentController 一致）。
 */
@RestController
@RequiredArgsConstructor
public class FavoriteController {

    private final FavoriteService favoriteService;

    /** 我的收藏列表：{ok,total,items[]}；每项带商品名/价格/主图/状态/下架原因/是否可购买/收藏时间。 */
    @GetMapping("/api/favorites")
    public Map<String, Object> list(HttpServletRequest request) {
        return favoriteService.list(token(request));
    }

    /** 收藏商品：入参 {productId}；已收藏直接成功（G15 去重不报错）。 */
    @PostMapping("/api/favorites")
    public Map<String, Object> add(HttpServletRequest request, @RequestBody(required = false) Map<String, Object> body) {
        Object value = body == null ? null : body.get("productId");
        Long productId = value instanceof Number number ? number.longValue() : null;
        return favoriteService.add(token(request), productId);
    }

    /** 取消收藏：按商品 id 删除当前账号自己的收藏记录（幂等，未收藏也返回成功）。 */
    @DeleteMapping("/api/favorites/{productId}")
    public Map<String, Object> remove(HttpServletRequest request, @PathVariable long productId) {
        return favoriteService.remove(token(request), productId);
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
