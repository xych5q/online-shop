package com.group1.onlineshop.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 订单操作审计日志（P2 关键操作留痕，W3-2/W3-3 落点）。
 *
 * <p>P2 六项审计中本表承载订单域两项：
 * <ul>
 *   <li>订单状态推进（ADVANCE）——每次商家推进写一条</li>
 *   <li>订单取消（CANCEL）——买家取消、商家单笔取消、批量取消逐单各写一条</li>
 * </ul>
 *
 * <p>P1 口径只约束订单表只记 {@code createdAt} 一个时间字段（见 {@link Order}），
 * 本表为审计专用表，自带记录时间；各状态变更时间如需追溯从本表取，不在订单表冗余。
 *
 * <p>不建数据库外键（沿用本项目 id 引用风格）：{@code orderId} 有效性由应用层保证。
 */
@Data
@Entity
@Table(name = "order_audit_log")
public class OrderAuditLog {

    public enum Action {
        /** 订单状态推进（商家） */
        ADVANCE,
        /** 订单取消（买家 / 商家单笔 / 批量逐单） */
        CANCEL
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 订单 id（Order.id） */
    @Column(nullable = false)
    private Long orderId;

    /** 订单号（冗余，便于按单检索） */
    @Column(nullable = false, length = 40)
    private String orderNo;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Action action;

    /** 变更前状态（取消时为取消前所处状态） */
    @Column(nullable = false, length = 20)
    private String fromStatus;

    /** 变更后状态（推进的下一状态 / 取消固定 CANCELLED） */
    @Column(nullable = false, length = 20)
    private String toStatus;

    /** 操作者类型：BUYER / SELLER */
    @Column(nullable = false, length = 10)
    private String operatorType;

    /** 操作者用户名 */
    @Column(nullable = false, length = 50)
    private String operatorName;

    /** 留痕时间（本表唯一时间字段，P1 不受影响） */
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
