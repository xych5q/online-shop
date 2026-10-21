package com.group1.onlineshop.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 商品图片（挑战需求 A · A.1.5：商品图片可以多张）。
 *
 * <p>一件商品至多 9 张（A.1.5-2），JPG/PNG、单张 ≤5MB（A.1.5-3），
 * 可以一张都不传、前端显示占位图（A.1.5-4）。
 * <b>主图 = {@code sortOrder} 最小的一条</b>，列表与搜索只显示主图，详情页显示全部（A.1.5-2）。
 *
 * <p>为何独立成表而不在 Product 里放字符串列表：富文本描述本身会内嵌图片，
 * 后续若要图集管理或改主图，独立表更稳；同时便于把旧系统的单图字段迁移为第一条记录。
 */
@Data
@Entity
@Table(name = "product_image")
public class ProductImage {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long productId;

    /** 文件名，实际文件存于 app.upload.dir */
    @Column(nullable = false, length = 255)
    private String filename;

    /** 排序号，越小越靠前；最小值即主图 */
    @Column(nullable = false)
    private Integer sortOrder = 0;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();
}
