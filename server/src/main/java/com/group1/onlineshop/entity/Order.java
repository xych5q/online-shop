package com.group1.onlineshop.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 订单：买家从购物车勾选多件提交后生成的一笔交易记录，含多条 {@link OrderItem} 明细，
 * 整单共用一套状态（SRS FR-D7、D5）。下单时按件数扣减库存，取消时按原件数回补。
 *
 * <p><b>订单状态机</b>（SRS 4.2、FR-S6）：五个状态严格单向线性推进，不允许回退；
 * 另有终态「已取消」（客户在前三个状态取消，或商家在交易完成前取消）。
 * <pre>
 * PLACED 客户下单 → CONFIRMED 商家确认 → PREPARED 备货完成 → SHIPPING 开始发货 → COMPLETED 交易完成
 *    \                  \                    \
 *     +------------------+--------------------+--------→ CANCELLED 已取消（终态）
 * </pre>
 * 开始发货只是标记线下交付起点，客户自此不可取消；商家可取消至交易完成前。
 *
 * <p>最终结果只有「交易完成」或「已取消」二值（SRS FR-D4），「失败」不再保留；
 * 由状态推导（COMPLETED→交易完成，CANCELLED→已取消），故不单独存字段。
 *
 * <p>不建数据库外键（沿用本项目 id 引用风格）：{@code buyerId} 指向 Buyer，有效性由应用层保证。
 * 价格不设快照（FR-C11）：商品发布后价格不可改，明细显示商品当前价格，故订单不冗余单价。
 */
@Data
@Entity
@Table(name = "orders")
public class Order {

    public enum Status {
        /** 客户下单 */
        PLACED,
        /** 商家确认 */
        CONFIRMED,
        /** 备货完成 */
        PREPARED,
        /** 开始发货（客户自此不可取消） */
        SHIPPING,
        /** 交易完成（终态，不产生库存动作） */
        COMPLETED,
        /** 已取消（终态，库存按原件数回补） */
        CANCELLED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 订单号，全局唯一，展示与检索用 */
    @Column(nullable = false, unique = true, length = 40)
    private String orderNo;

    /** 下单客户 id（Buyer.id） */
    @Column(nullable = false)
    private Long buyerId;

    /** 下单时共用的默认交易地点（FR-D7，不填，取账号默认地点的一次快照） */
    @Column(length = 200)
    private String location;

    /** 订单总金额（下单时按各明细件数 × 商品当前价计算） */
    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.PLACED;

    /** 下单时间；买家历史与卖家订单列表按此倒序（FR-D4、FR-S5） */
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 状态最后一次推进的时间 */
    private LocalDateTime updatedAt;

    /** 取消时间；已取消订单保留在历史中，不删除（FR-D4、G4） */
    private LocalDateTime cancelledAt;
}
