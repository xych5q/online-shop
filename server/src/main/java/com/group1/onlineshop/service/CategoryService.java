package com.group1.onlineshop.service;

import com.group1.onlineshop.dto.CategoryCreateRequest;
import com.group1.onlineshop.dto.CategoryUpdateRequest;
import com.group1.onlineshop.entity.Category;
import com.group1.onlineshop.exception.BizException;
import com.group1.onlineshop.repository.CategoryRepository;
import com.group1.onlineshop.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 类别管理服务（卖家后台 · 挑战需求 A.1.2：两级类别的增 / 改名 / 删）。
 * 规则以 docs/design/接口设计-卖家后台管理.md 为准：
 *   - 新增：parentId 为空建一级类别，非空建二级类别（父必须存在且为一级）；同级不允许重名；
 *   - 改名：仅改名称，同级不允许重名（与自己原名相同视为无变化）；
 *   - 删除：有子类别或有商品均不可删（A.1.2-3），商品需先改挂其他类别（A.1.2-4）。
 * DTO 注解校验由全局异常处理器先行拦截，本服务内校验为二次防御。
 */
@Service
@RequiredArgsConstructor
public class CategoryService {

    private final CategoryRepository categoryRepository;
    private final ProductRepository productRepository;

    /**
     * 新增类别。
     * 规则：name 去空格后必填且 ≤50 字；parentId 非空时父类别必须存在（404）且为一级类别（400）；
     * 同一父级下不允许重名（400「同级类别名称已存在」）。
     * 返回 {ok:true, id, name, parentId, createdAt}。
     */
    public Map<String, Object> create(CategoryCreateRequest req) {
        String name = req.getName() == null ? "" : req.getName().trim();
        Long parentId = req.getParentId();

        if (name.isEmpty()) {
            throw new BizException("类别名称必填");
        }
        if (name.length() > 50) {
            throw new BizException("类别名称不能超过 50 字");
        }
        if (parentId != null) {
            Category parent = categoryRepository.findById(parentId)
                    .orElseThrow(() -> new BizException("父类别不存在", 404));
            if (parent.getParentId() != null) {
                throw new BizException("父类别必须是一级类别");
            }
        }
        if (categoryRepository.existsByParentIdAndName(parentId, name)) {
            throw new BizException("同级类别名称已存在");
        }

        Category category = new Category();
        category.setName(name);
        category.setParentId(parentId);
        categoryRepository.save(category);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("id", category.getId());
        result.put("name", category.getName());
        result.put("parentId", category.getParentId());
        result.put("createdAt", category.getCreatedAt());
        return result;
    }

    /**
     * 修改类别（仅改名）。
     * 规则：类别不存在 404「类别不存在」；名称与原名相同视为无变化直接成功；
     * 同级重名 400「同级类别名称已存在」。
     */
    public Map<String, Object> rename(long id, CategoryUpdateRequest req) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new BizException("类别不存在", 404));
        String name = req.getName() == null ? "" : req.getName().trim();

        if (name.isEmpty()) {
            throw new BizException("类别名称必填");
        }
        if (name.length() > 50) {
            throw new BizException("类别名称不能超过 50 字");
        }
        if (!name.equals(category.getName())) {
            if (categoryRepository.existsByParentIdAndName(category.getParentId(), name)) {
                throw new BizException("同级类别名称已存在");
            }
            category.setName(name);
            categoryRepository.save(category);
        }
        return ok();
    }

    /**
     * 删除类别。
     * 规则：类别不存在 404「类别不存在」；
     * 一级类别下还有子类别 400「请先删除该类别下的子类别」；
     * 该类别（或其一、二级子树）下还有商品 400「该类别下还有商品，不能删除」。
     */
    public Map<String, Object> delete(long id) {
        Category category = categoryRepository.findById(id)
                .orElseThrow(() -> new BizException("类别不存在", 404));

        if (category.getParentId() == null) {
            // 一级类别：先保证没有子类别，再保证子树下没有商品
            if (categoryRepository.existsByParentId(category.getId())) {
                throw new BizException("请先删除该类别下的子类别");
            }
            for (Category child : categoryRepository.findByParentIdOrderByIdAsc(category.getId())) {
                if (productRepository.countByCategoryId(child.getId()) > 0) {
                    throw new BizException("该类别下还有商品，不能删除");
                }
            }
        } else {
            // 二级类别：直接检查自己名下有没有商品
            if (productRepository.countByCategoryId(category.getId()) > 0) {
                throw new BizException("该类别下还有商品，不能删除");
            }
        }

        categoryRepository.delete(category);
        return ok();
    }

    /** 统一成功返回 {ok: true} */
    private Map<String, Object> ok() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("ok", true);
        return m;
    }
}
