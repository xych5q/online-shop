package com.group1.onlineshop.repository;

import com.group1.onlineshop.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;
import java.util.Optional;

/**
 * 商品仓储接口。
 *
 * <p>挑战需求 A 升级要点（A.1.1-1）：<b>可同时发布多个商品</b>，多件在售、多笔交易并行。
 * 因此原本「同一时间最多一条在售商品」的单品查询方法<b>保留但不再用于唯一性校验</b>，
 * 仅供仍需「当前商品」语义的旧页面使用；新代码应改用按状态列表 / 类别 / 关键词查询。
 *
 * <p>方法兼容说明：原有 4 个方法一个未删。
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    // ── 原有方法（基线遗留，保留不动） ─────────────────────────────────────

    /** 当前活跃商品（在售或已冻结）：单品模式下的取用方式（A 之后不再用于唯一性校验） */
    Optional<Product> findFirstByStatusInOrderByCreatedAtAsc(List<Product.Status> statuses);

    /** 是否存在处于任一指定状态的商品 */
    boolean existsByStatusIn(List<Product.Status> statuses);

    /** 历史商品总数（已下架） */
    long countByStatus(Product.Status status);

    /** 历史商品分页查询（配合 Pageable 实现 closedAt 倒序、每页 10 条） */
    Page<Product> findByStatus(Product.Status status, Pageable pageable);

    // ── 挑战需求 A 新增 ──────────────────────────────────────────────────

    /**
     * 浏览 / 搜索（A.1.4）：在售商品分页，按发布时间倒序（A.1.4-3 本组裁定倒序）。
     * 关键词同时匹配名称与描述，模糊匹配、忽略大小写（A.1.4-1 / A.1.4-2 本组裁定）。
     * {@code keyword} 为空或 null 时不参与过滤。
     */
    @Query("""
            select p from Product p
            where p.status = com.group1.onlineshop.entity.Product$Status.ON_SALE
              and (:keyword is null or :keyword = ''
                   or lower(p.name) like lower(concat('%', :keyword, '%'))
                   or lower(p.description) like lower(concat('%', :keyword, '%')))
              and (:categoryId is null or p.categoryId = :categoryId)
            order by p.createdAt desc, p.id desc
            """)
    Page<Product> searchOnSale(@Param("keyword") String keyword,
                               @Param("categoryId") Long categoryId,
                               Pageable pageable);

    /**
     * 按一级类别筛选：需同时命中该一级类别自身及其下所有二级类别。
     * 由调用方传入二级类别 id 列表（本方法不做递归查询，避免 N+1）。
     */
    @Query("""
            select p from Product p
            where p.status = com.group1.onlineshop.entity.Product$Status.ON_SALE
              and p.categoryId in :categoryIds
            order by p.createdAt desc, p.id desc
            """)
    Page<Product> findOnSaleByCategoryIds(@Param("categoryIds") List<Long> categoryIds, Pageable pageable);

    /** 某类别下是否还有商品（删除类别前校验，A.1.2-3「挂着商品的类别不能直接删」） */
    long countByCategoryId(Long categoryId);

    /** 该类别下的全部商品（删除类别前提示用） */
    List<Product> findByCategoryIdOrderByCreatedAtDesc(Long categoryId);

    /** 全部在售商品（供卖家后台多商品列表） */
    List<Product> findByStatusOrderByCreatedAtDesc(Product.Status status);

    /** 按 id 列表取商品（订单历史回填商品名等） */
    List<Product> findByIdIn(List<Long> ids);
}
