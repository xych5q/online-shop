package com.group1.onlineshop.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 购物车条目：挂在买家账号下的待选商品（SRS FR-D10、G10、G12）。
 *
 * <p>规则依据：
 * <ul>
 *   <li>FR-D10 按账号存储、换设备一致；条目含名称/单价/数量/小计，名称与单价按 productId 读当前值，不冗余</li>
 *   <li>G10 同一商品重复加入合并为一条并累加数量，故 {@code buyerId + productId} 在应用层保证唯一</li>
 *   <li>FR-D10 加购阶段不校验库存、不预占、不设价格快照；售罄/下架在结算时拦截</li>
 *   <li>FR-D11 可勾选多条一次转收藏，转后该项移出购物车</li>
 * </ul>
 *
 * <p>不建数据库外键（沿用本项目 id 引用风格）：{@code buyerId}、{@code productId} 有效性由应用层保证。
 */
@Data
@Entity
@Table(name = "cart_item")
public class CartItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 所属买家 id（Buyer.id） */
    @Column(nullable = false)
    private Long buyerId;

    /** 商品 id（Product.id） */
    @Column(nullable = false)
    private Long productId;

    /** 数量，可调整（G10 重复加购时累加） */
    @Column(nullable = false)
    private Integer quantity;

    /** 加入时间 */
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
