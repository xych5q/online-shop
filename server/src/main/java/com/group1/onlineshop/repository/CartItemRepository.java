package com.group1.onlineshop.repository;

import com.group1.onlineshop.entity.CartItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

/** 购物车仓储（挑战需求 B）。 */
public interface CartItemRepository extends JpaRepository<CartItem, Long> {

    /** 某买家购物车的全部条目（按加入时间，展示稳定） */
    List<CartItem> findAllByBuyerIdOrderByCreatedAtAsc(Long buyerId);

    /** 重复加购判重：同账号同商品合并累加数量（G10） */
    Optional<CartItem> findByBuyerIdAndProductId(Long buyerId, Long productId);

    /** 是否存在同账号同商品条目（G10 去重判断） */
    boolean existsByBuyerIdAndProductId(Long buyerId, Long productId);
}
