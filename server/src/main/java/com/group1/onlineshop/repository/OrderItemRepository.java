package com.group1.onlineshop.repository;

import com.group1.onlineshop.entity.OrderItem;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/** 订单明细仓储（挑战需求 B）。 */
public interface OrderItemRepository extends JpaRepository<OrderItem, Long> {

    /** 某订单的全部明细（订单列表展示商品清单、取消回补库存用） */
    List<OrderItem> findByOrderId(Long orderId);

    /** 一次取多笔订单的明细（订单列表分页避免 N+1） */
    List<OrderItem> findByOrderIdIn(List<Long> orderIds);

    /** 按商品统计关联明细（FR-S8 历史商品·关联订单明细） */
    List<OrderItem> findByProductId(Long productId);
}
