package com.group1.onlineshop.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 发布商品请求 DTO（多商品发布接口，对齐挑战需求 A 的数据模型）。
 *
 * <p>校验规则（对应需求）：
 * <ol>
 *   <li>类别必填（A.1.2-2）：categoryId 必须为已存在的<b>二级类别</b> id，缺失拦截；</li>
 *   <li>库存校验（A.1.3-1）：stock 必须 ≥1，传 0 或负数直接拦截；</li>
 *   <li>多图限制（A.1.5-2 / A.1.5-4）：images 可选（不传显示占位图），传入时最多 9 张；</li>
 *   <li>富文本描述（A.1.5-1）：richDescription 支持 HTML/Markdown 长文本（≤8000 字）。</li>
 * </ol>
 *
 * <p>图片沿用项目现有约定：每张为 data:image/(jpeg|png);base64,... 字符串，
 * 由服务层解码后写入上传目录（单张 ≤5MB），文件名写入 product_image 表。
 *
 * <p>注解校验失败时由 GlobalExceptionHandler 统一返回 400 + 第一条错误文案。
 */
@Data
public class ProductPublishRequest {

    /** 商品名称：必填，≤100 字 */
    @NotBlank(message = "商品名称必填")
    @Size(max = 100, message = "商品名称不能超过 100 字")
    private String name;

    /** 所属二级类别 id：必填（类别必须存在且为二级，由服务层进一步校验） */
    @NotNull(message = "商品类别必填")
    private Long categoryId;

    /** 简要描述（列表/卡片展示用）：可选，≤2000 字 */
    @Size(max = 2000, message = "商品描述不能超过 2000 字")
    private String description;

    /** 商品详情（富文本，支持 HTML/Markdown，可内嵌图片）：可选，≤8000 字 */
    @Size(max = 8000, message = "商品详情不能超过 8000 字")
    private String richDescription;

    /** 价格：必填，必须 >0（服务层统一 HALF_UP 保留两位小数） */
    @NotNull(message = "价格必填")
    @DecimalMin(value = "0", inclusive = false, message = "价格必须为大于 0 的数字")
    private BigDecimal price;

    /** 库存：必填，必须 ≥1（0 或负数拦截） */
    @NotNull(message = "库存必填")
    @Min(value = 1, message = "库存必须大于或等于 1")
    private Integer stock;

    /** 商品图片（base64 data URL 数组）：可选——不传时前端显示占位图（A.1.5-4）；传入时最多 9 张，且每张内容不能为空 */
    @Size(max = 9, message = "图片数量不能超过 9 张")
    private List<@NotBlank(message = "图片内容不能为空") String> images;
}
