package com.group1.onlineshop.repository;

import com.group1.onlineshop.entity.Product;
import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
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
 *
 * <p><b>动态查询</b>：继承 {@link JpaSpecificationExecutor} 以支持
 * {@code findAll(Specification, Pageable)}。浏览与搜索域（J-1）的关键词 + 类别组合筛选
 * 用 Specification 拼条件更清晰，且「选一级类别时含其全部二级」这类动态集合条件
 * 用 {@code @Query} 写会很难维护。其它域若要组合筛选也可直接复用。
 */
public interface ProductRepository extends JpaRepository<Product, Long>, JpaSpecificationExecutor<Product> {

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

    /**
     * 悲观锁读取商品（交易域专用：自动推进交易、标记交易结果）。
     *
     * <p><b>为什么需要当前读</b>：W-4 的首笔自动触发发生在「提交意向」事务内。两个买家并发提交时，
     * 后到者在 JVM 锁内做普通查询仍可能命中提交前的快照（商品还是 {@code ON_SALE}），
     * 于是一条队列被推进出两笔 {@code IN_TRANSACTION}——正是 W-7 压测在基线版上测出的缺陷。
     * 加 {@code FOR UPDATE} 后，后到者会等前者事务提交，从而读到 {@code FROZEN + frozenBy='auto'}，
     * 幂等返回「已有交易」，本笔正常留在队列。
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Product p where p.id = :id")
    Optional<Product> lockById(@Param("id") Long id);
}
