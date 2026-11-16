package com.group1.onlineshop.repository;

import com.group1.onlineshop.entity.Favorite;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

/** 收藏仓储（挑战需求 B）。 */
public interface FavoriteRepository extends JpaRepository<Favorite, Long> {

    /** 某买家的收藏列表（按收藏时间，FR-D9） */
    List<Favorite> findAllByBuyerIdOrderByCreatedAtDesc(Long buyerId);

    /** 去重：同账号同商品只保留一条（G15、FR-D11 转收藏去重） */
    Optional<Favorite> findByBuyerIdAndProductId(Long buyerId, Long productId);

    /** 是否已收藏（收藏/转收藏去重判断） */
    boolean existsByBuyerIdAndProductId(Long buyerId, Long productId);
}
