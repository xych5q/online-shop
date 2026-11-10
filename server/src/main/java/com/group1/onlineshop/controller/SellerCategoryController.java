package com.group1.onlineshop.controller;

import com.group1.onlineshop.dto.CategoryCreateRequest;
import com.group1.onlineshop.dto.CategoryUpdateRequest;
import com.group1.onlineshop.service.CategoryService;
import com.group1.onlineshop.service.SellerService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 卖家端类别管理控制器：3 个 /api/seller/categories 接口（挑战需求 A · A.1.2）。
 * 规则以 docs/design/接口设计-卖家后台管理.md 为准：
 *   - 新增：parentId 为空建一级类别，非空建二级类别；同级不允许重名；
 *   - 修改：仅改名；同级不允许重名；
 *   - 删除：有子类别或有商品均不可删（A.1.2-3，商品需先改挂其他类别）。
 * 鉴权：与 SellerController 一致，X-Token（或 Authorization: Bearer）。
 */
@RestController
@RequiredArgsConstructor
public class SellerCategoryController {

    private final SellerService sellerService;
    private final CategoryService categoryService;

    /**
     * 新增类别：入参 CategoryCreateRequest {name, parentId?}。
     * 成功返回 {ok:true, id, name, parentId, createdAt}。
     */
    @PostMapping("/api/seller/categories")
    public Map<String, Object> create(HttpServletRequest request,
                                      @Valid @RequestBody CategoryCreateRequest body) {
        requireAuth(request);
        return categoryService.create(body);
    }

    /**
     * 修改类别（改名）：入参 CategoryUpdateRequest {name}。
     * 规则：类别不存在 404「类别不存在」；同级重名 400「同级类别名称已存在」。
     */
    @PutMapping("/api/seller/categories/{id}")
    public Map<String, Object> rename(HttpServletRequest request, @PathVariable long id,
                                      @Valid @RequestBody CategoryUpdateRequest body) {
        requireAuth(request);
        return categoryService.rename(id, body);
    }

    /**
     * 删除类别：规则见类注释。一级类别下还有子类别 400「请先删除该类别下的子类别」；
     * 该类别下有商品 400「该类别下还有商品，不能删除」。
     */
    @DeleteMapping("/api/seller/categories/{id}")
    public Map<String, Object> delete(HttpServletRequest request, @PathVariable long id) {
        requireAuth(request);
        return categoryService.delete(id);
    }

    /** 鉴权：token 无效时由 SellerService 抛 401「未登录或登录已失效」 */
    private void requireAuth(HttpServletRequest request) {
        sellerService.requireAuth(token(request));
    }

    /** 从 X-Token 或 Authorization: Bearer 头取 token（与 SellerController 一致） */
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
