package com.group1.onlineshop.dto;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 商品改挂类别请求 DTO（卖家后台管理 · A.1.2-4 商品可改挂到其他类别）。
 * 目标类别必须存在且为二级类别（由服务层查库校验）。
 */
@Data
public class ProductCategoryUpdateRequest {

    /** 目标二级类别 id：必填 */
    @NotNull(message = "商品类别必填")
    private Long categoryId;
}
