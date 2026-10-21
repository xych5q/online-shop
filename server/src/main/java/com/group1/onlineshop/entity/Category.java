package com.group1.onlineshop.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 商品类别（挑战需求 A · A.1.2：支持 2 级类别管理）。
 *
 * <p>两级结构：{@code parentId} 为 {@code null} 表示一级类别，非空表示二级类别。
 * 商品必须挂到某个二级类别下（A.1.2-2「类别必填」），该约束由应用层校验，
 * 不在数据库层加外键——删除类别时需先处理其下商品（A.1.2-3）。
 *
 * <p>需求依据：
 * <ul>
 *   <li>A.1.2-1 类别由卖家在后台自行维护（新增 / 重命名 / 删除），非平台预设</li>
 *   <li>A.1.2-2 商品必挂二级类别，不允许「不选类别」</li>
 *   <li>A.1.2-3 挂着商品的类别不能直接删除</li>
 *   <li>A.1.2-4 商品可改挂到其他类别，故删类别前可先改挂</li>
 * </ul>
 */
@Data
@Entity
@Table(name = "category")
public class Category {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 50)
    private String name;

    /** null = 一级类别；非空 = 该一级类别下的二级类别 */
    @Column
    private Long parentId;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
