package com.group1.onlineshop.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 订单明细：一笔 {@link Order} 内的单件商品记录。一单多条明细，整单共用一套状态。
 *
 * <p>不冗余商品名称与单价（FR-C11：商品发布后价格不可改，订单不设价格快照，
 * 展示时按 productId 读商品当前价与名称）。数量在下单时确定，取消按此件数回补库存。
 *
 * <p>不建数据库外键（沿用本项目 id 引用风格）：{@code orderId} 指向 Order、
 * {@code productId} 指向 Product，有效性由应用层保证。
 */
@Data
@Entity
@Table(name = "order_item")
public class OrderItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属订单 id（Order.id） */
    @Column(nullable = false)
    private Long orderId;

    /** 商品 id（Product.id） */
    @Column(nullable = false)
    private Long productId;

    /** 下单件数（整单取消时按此回补库存） */
    @Column(nullable = false)
    private Integer quantity;
}
