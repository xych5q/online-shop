package com.group1.onlineshop.controller;

import com.group1.onlineshop.service.CatalogService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 浏览与搜索域控制器（任务 J-1）：3 个只读查询接口。
 *
 * <pre>
 * GET /api/products        商品列表：关键词 + 类别 + 分页（FR-C6 / FR-C7 / FR-C8）
 * GET /api/products/{id}   商品详情：含全部图片与富文本描述（FR-C9 / FR-C10）
 * GET /api/categories/tree 类别树：两级（支撑 FR-C8 的类别筛选）
 * </pre>
 *
 * <p>契约见 [[接口设计]] v2「浏览与搜索域」。本控制器只做参数接收与转发，
 * 全部查询逻辑在 {@link CatalogService}，不触碰实体与仓储。
 */
@RestController
@RequiredArgsConstructor
public class CatalogController {

    private final CatalogService catalogService;

    /**
     * 商品列表。
     *
     * @param keyword    关键词，空格分词后多词 AND，匹配名称与描述（FR-C7）
     * @param categoryId 类别 id，传一级类别时命中其下全部二级（FR-C8）
     * @param page       页码，从 1 开始
     * @param pageSize   每页条数，默认 12、上限 50
     */
    @GetMapping("/api/products")
    public Map<String, Object> list(@RequestParam(required = false) String keyword,
                                    @RequestParam(required = false) Long categoryId,
                                    @RequestParam(defaultValue = "1") int page,
                                    @RequestParam(defaultValue = "12") int pageSize) {
        return catalogService.list(keyword, categoryId, page, pageSize);
    }

    /** 商品详情；商品不存在、已下架或售罄时返回 404 */
    @GetMapping("/api/products/{id}")
    public Map<String, Object> detail(@PathVariable Long id) {
        return catalogService.detail(id);
    }

    /** 类别树（两级，按 id 升序） */
    @GetMapping("/api/categories/tree")
    public Map<String, Object> tree() {
        return catalogService.tree();
    }
}
