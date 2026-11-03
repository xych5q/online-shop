package com.group1.onlineshop.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

/**
 * 库存调整流水（挑战需求 A.1.3-5 手动调库存留痕）。
 * 一条流水记录一次调整：谁、何时、从多少调到多少、原因。
 * 不加数据库外键（与 categoryId 同风格，productId 有效性由应用层保证）。
 */
@Entity
@Table(name = "stock_log")
@Getter
@Setter
public class StockLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** 被调整的商品 id */
    @Column(nullable = false)
    private Long productId;

    /** 操作人（卖家用户名，当前系统单卖家） */
    @Column(nullable = false, length = 50)
    private String operator;

    /** 调整前库存 */
    @Column(nullable = false)
    private Integer beforeStock;

    /** 调整后库存 */
    @Column(nullable = false)
    private Integer afterStock;

    /** 变动量 = afterStock - beforeStock，可为负（调减） */
    @Column(nullable = false)
    private Integer changeAmount;

    /** 调整原因（补货 / 盘点 / 损耗等），可选 */
    @Column(length = 200)
    private String reason;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
