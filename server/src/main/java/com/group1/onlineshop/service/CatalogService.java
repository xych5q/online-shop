package com.group1.onlineshop.service;

import com.group1.onlineshop.entity.Category;
import com.group1.onlineshop.entity.Product;
import com.group1.onlineshop.entity.ProductImage;
import com.group1.onlineshop.exception.BizException;
import com.group1.onlineshop.repository.CategoryRepository;
import com.group1.onlineshop.repository.ProductImageRepository;
import com.group1.onlineshop.repository.ProductRepository;
import jakarta.persistence.criteria.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 浏览与搜索域查询服务（任务 J-1）：商品列表、商品详情、类别树。
 *
 * <p>需求依据：
 * <ul>
 *   <li>FR-C6 列表展示名称、价格、主图、类别，默认按发布时间倒序</li>
 *   <li>FR-C7 关键词同时匹配名称与描述，模糊匹配 + 空格分词（多关键词 AND）</li>
 *   <li>FR-C8 列表支持按二级类别筛选</li>
 *   <li>FR-C9 列表与搜索只显示主图（sortOrder 最小的一张），详情页显示全部图片</li>
 *   <li>FR-C10 详情返回富文本描述 richDescription</li>
 * </ul>
 *
 * <p><b>只读</b>：本服务不改动任何商品状态，不涉及意向与交易；
 * 也不新增、不修改任何实体与仓储（数据模型归 C-1，类别维护归 L-2）。
 */
@Service
@RequiredArgsConstructor
public class CatalogService {

    private static final int DEFAULT_PAGE_SIZE = 12;
    private static final int MAX_PAGE_SIZE = 50;

    /**
     * 买家可见状态：在售 + 交易中。
     * 售罄（SOLD_OUT）按 FR-C4 属「库存归零自动下架」，与已下架（OFF_SHELF）一样不进浏览列表；
     * 若后续裁定「售罄商品仍可浏览」，在此常量中加入 Product.Status.SOLD_OUT 即可。
     */
    private static final List<Product.Status> VISIBLE =
            List.of(Product.Status.ON_SALE, Product.Status.FROZEN);

    private final ProductRepository productRepository;
    private final CategoryRepository categoryRepository;
    private final ProductImageRepository productImageRepository;

    /**
     * 商品列表：关键词（空格分词，多词 AND，匹配名称与描述）+ 类别（选一级含其全部二级）+ 分页。
     * 出参 {items[], page, pageSize, total, totalPages}，按发布时间倒序、同时间按 id 倒序。
     */
    public Map<String, Object> list(String keyword, Long categoryId, int page, int pageSize) {
        int size = pageSize <= 0 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        int index = Math.max(page - 1, 0);

        List<String> terms = splitKeyword(keyword);
        Set<Long> categoryIds = resolveCategoryIds(categoryId);
        Map<Long, String> categoryNames = categoryNames();

        Page<Product> p = productRepository.findAll(
                spec(terms, categoryIds),
                PageRequest.of(index, size, Sort.by(Sort.Direction.DESC, "createdAt", "id")));

        List<Map<String, Object>> items = p.getContent().stream()
                .map(it -> toListItem(it, categoryNames))
                .collect(Collectors.toList());

        Map<String, Object> res = new LinkedHashMap<>();
        res.put("items", items);
        res.put("page", p.getNumber() + 1);
        res.put("pageSize", p.getSize());
        res.put("total", p.getTotalElements());
        res.put("totalPages", p.getTotalPages());
        return res;
    }

    /** 商品详情：{product:{...含全部图片与富文本描述}}；不存在、已下架或售罄 → 404 */
    public Map<String, Object> detail(Long id) {
        Product p = productRepository.findById(id)
                .orElseThrow(() -> new BizException("商品不存在或已下架", 404));
        if (!VISIBLE.contains(p.getStatus())) {
            throw new BizException("商品不存在或已下架", 404);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("product", toDetail(p, categoryNames()));
        return res;
    }

    /**
     * 类别树：{categories:[{id,name,children:[{id,name}]}]}。
     * 两级，均按 id 升序（类别无排序字段，按创建顺序展示）。
     */
    public Map<String, Object> tree() {
        List<Category> roots = categoryRepository.findByParentIdIsNullOrderByIdAsc();
        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Category c : roots) {
            Map<String, Object> node = new LinkedHashMap<>();
            node.put("id", c.getId());
            node.put("name", c.getName());
            node.put("children", categoryRepository.findByParentIdOrderByIdAsc(c.getId()).stream()
                    .map(kid -> {
                        Map<String, Object> n = new LinkedHashMap<>();
                        n.put("id", kid.getId());
                        n.put("name", kid.getName());
                        return n;
                    })
                    .collect(Collectors.toList()));
            nodes.add(node);
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("categories", nodes);
        return res;
    }

    /* ---------- 内部方法 ---------- */

    /** 关键词按空格切分，空串与空白过滤掉；返回空列表表示不过滤（FR-C7） */
    private List<String> splitKeyword(String keyword) {
        if (keyword == null || keyword.trim().isEmpty()) {
            return List.of();
        }
        return List.of(keyword.trim().split("\\s+"));
    }

    /** 选中一级类别时返回「其下全部二级」的 id 集合；选中二级时只返回自身；未选返回空集（不过滤） */
    private Set<Long> resolveCategoryIds(Long categoryId) {
        if (categoryId == null) {
            return Set.of();
        }
        Category target = categoryRepository.findById(categoryId).orElse(null);
        if (target == null) {
            return Set.of();
        }
        if (target.getParentId() != null) {
            return Set.of(target.getId());
        }
        return categoryRepository.findByParentIdOrderByIdAsc(target.getId()).stream()
                .map(Category::getId)
                .collect(Collectors.toSet());
    }

    private Map<Long, String> categoryNames() {
        return categoryRepository.findAll().stream()
                .collect(Collectors.toMap(Category::getId, Category::getName, (a, b) -> a));
    }

    /** 动态条件：状态可见 + 每个关键词都要命中名称或描述（AND）+ 类别集合 */
    private Specification<Product> spec(List<String> terms, Set<Long> categoryIds) {
        return (root, query, cb) -> {
            List<Predicate> ps = new ArrayList<>();
            ps.add(root.get("status").in(VISIBLE));
            for (String term : terms) {
                String like = "%" + term.toLowerCase() + "%";
                ps.add(cb.or(
                        cb.like(cb.lower(root.get("name")), like),
                        cb.like(cb.lower(cb.coalesce(root.get("description"), cb.literal(""))), like)));
            }
            if (!categoryIds.isEmpty()) {
                ps.add(root.get("categoryId").in(categoryIds));
            }
            return cb.and(ps.toArray(new Predicate[0]));
        };
    }

    /** 列表项：名称、价格、主图、类别（FR-C6 / FR-C9），主图缺失时回退旧单图字段 */
    private Map<String, Object> toListItem(Product p, Map<Long, String> categoryNames) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.getId());
        m.put("name", p.getName());
        m.put("price", p.getPrice());
        m.put("imageUrl", mainImageUrl(p));
        m.put("status", p.getStatus().name().toLowerCase());
        m.put("categoryId", p.getCategoryId());
        m.put("categoryName", p.getCategoryId() == null ? null : categoryNames.get(p.getCategoryId()));
        m.put("stock", p.getStock());
        m.put("publishedAt", p.getCreatedAt());
        return m;
    }

    /** 详情：列表字段 + 简要描述 + 富文本描述 + 全部图片（FR-C9 / FR-C10） */
    private Map<String, Object> toDetail(Product p, Map<Long, String> categoryNames) {
        Map<String, Object> m = toListItem(p, categoryNames);
        m.put("description", p.getDescription());
        m.put("richDescription", p.getRichDescription());
        m.put("images", productImageRepository.findByProductIdOrderBySortOrderAscIdAsc(p.getId()).stream()
                .map(img -> "/uploads/" + img.getFilename())
                .collect(Collectors.toList()));
        return m;
    }

    /** 主图 = sortOrder 最小的一张；无图时用旧单图字段 image；都没有则返回 null（前端显示占位图） */
    private String mainImageUrl(Product p) {
        return productImageRepository.findFirstByProductIdOrderBySortOrderAscIdAsc(p.getId())
                .map(ProductImage::getFilename)
                .or(() -> java.util.Optional.ofNullable(p.getImage()))
                .map(name -> "/uploads/" + name)
                .orElse(null);
    }
}
