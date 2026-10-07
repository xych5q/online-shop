package com.group1.onlineshop.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/** 商品。状态机：在售 → 已冻结 → 已下架。 */
@Data
@Entity
@Table(name = "product")
public class Product {

    public enum Status {
        /** 在售 */
        ON_SALE,
        /** 已冻结（手动或交易中） */
        FROZEN,
        /** 已下架（进历史） */
        OFF_SHELF
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(length = 2000)
    private String description;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    /** 图片文件名，存于 app.upload.dir；为空时前端显示占位图 */
    private String image;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ON_SALE;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime frozenAt;

    /** manual = 卖家手动冻结；auto = 进入交易自动冻结 */
    private String frozenBy;

    /** 下架时间（进入历史的时间） */
    private LocalDateTime closedAt;
}
