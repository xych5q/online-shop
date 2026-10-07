package com.group1.onlineshop.repository;

import com.group1.onlineshop.entity.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

/**
 * 商品仓储接口。
 * 说明：接口设计文档的「已有代码」清单中列有 ProductRepository，
 * 但仓库中实际缺失，由卖家端模块按文档约定补齐（方法与 IntentRepository 风格一致）。
 */
public interface ProductRepository extends JpaRepository<Product, Long> {

    /** 当前活跃商品（在售或已冻结）：单卖家单件在售，同一时间最多一条 */
    Optional<Product> findFirstByStatusInOrderByCreatedAtAsc(List<Product.Status> statuses);

    /** 是否存在处于任一指定状态的商品（发布前校验：已有在售或交易中需先下架） */
    boolean existsByStatusIn(List<Product.Status> statuses);

    /** 历史商品总数（已下架） */
    long countByStatus(Product.Status status);

    /** 历史商品分页查询（配合 Pageable 实现 closedAt 倒序、每页 10 条） */
    Page<Product> findByStatus(Product.Status status, Pageable pageable);
}
