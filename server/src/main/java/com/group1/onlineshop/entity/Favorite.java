package com.group1.onlineshop.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 收藏：挂在买家账号下的私有商品清单（SRS FR-D9、G9、G15）。
 *
 * <p>规则依据：
 * <ul>
 *   <li>FR-D9 按账号存储、换设备一致；独立列表，不并入订单历史；商品下架/售罄后保留并标注，不自动消失</li>
 *   <li>G15 同一商品已在收藏则去重、不重复报错，故 {@code buyerId + productId} 在应用层保证唯一</li>
 *   <li>FR-D11 购物车转收藏若已在收藏列表则去重</li>
 * </ul>
 *
 * <p>不冗余商品字段：展示时按 productId 读商品当前名称/价格/主图与状态（下架/售罄据此标注）。
 * 不建数据库外键（沿用本项目 id 引用风格）：{@code buyerId}、{@code productId} 有效性由应用层保证。
 */
@Data
@Entity
@Table(name = "favorite")
public class Favorite {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属买家 id（Buyer.id） */
    @Column(nullable = false)
    private Long buyerId;

    /** 商品 id（Product.id） */
    @Column(nullable = false)
    private Long productId;

    /** 收藏时间 */
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
