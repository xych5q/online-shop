package com.group1.onlineshop.repository;

import com.group1.onlineshop.entity.Order;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

/** 订单仓储（挑战需求 B）。 */
public interface OrderRepository extends JpaRepository<Order, Long> {

    /** 按订单号查（下单返回、取消校验订单号是否存在） */
    Optional<Order> findByOrderNo(String orderNo);

    /** 买家我的订单与历史：按账号、下单时间倒序分页（FR-D4） */
    Page<Order> findAllByBuyerIdOrderByCreatedAtDesc(Long buyerId, Pageable pageable);

    /** 按客户查看其全部订单（FR-D6 购买历史·客户维度） */
    List<Order> findAllByBuyerIdOrderByCreatedAtDesc(Long buyerId);

    /** 卖家订单列表：全部订单按下单时间倒序分页（FR-S5） */
    Page<Order> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /** 按状态统计（订单域进度口径） */
    long countByStatus(Order.Status status);

    /** 指定状态的订单（推进、批量取消预校验用） */
    List<Order> findAllByStatus(Order.Status status);
}
