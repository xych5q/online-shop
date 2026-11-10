package com.group1.onlineshop.repository;

import com.group1.onlineshop.entity.ProductImage;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

/**
 * 商品图片仓储（挑战需求 A · A.1.5 多图）。
 *
 * <p>主图规则：{@code sortOrder} 最小的一条（A.1.5-2）。
 * 查询统一按 sortOrder 升序，前端取第一条即主图。
 */
public interface ProductImageRepository extends JpaRepository<ProductImage, Long> {

    /** 某商品的全部图片，按排序号升序（第一条即主图） */
    List<ProductImage> findByProductIdOrderBySortOrderAscIdAsc(Long productId);

    /** 主图（排序号最小的一条） */
    Optional<ProductImage> findFirstByProductIdOrderBySortOrderAscIdAsc(Long productId);

    /** 某商品的图片张数（校验 ≤9 张上限） */
    long countByProductId(Long productId);

    /** 删除某商品的全部图片 */
    void deleteByProductId(Long productId);
}
