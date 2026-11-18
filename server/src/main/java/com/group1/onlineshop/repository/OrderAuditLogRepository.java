package com.group1.onlineshop.repository;

import com.group1.onlineshop.entity.OrderAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;

/** 订单操作审计仓储（P2 审计：订单状态推进、订单取消，W3-2/W3-3 落点）。 */
public interface OrderAuditLogRepository extends JpaRepository<OrderAuditLog, Long> {

    /** 按订单倒序取留痕（接口/验收演示用） */
    List<OrderAuditLog> findAllByOrderIdOrderByCreatedAtDesc(Long orderId);

    /** 按操作者取留痕（个人贡献核查用） */
    List<OrderAuditLog> findAllByOperatorNameOrderByCreatedAtDesc(String operatorName);
}
