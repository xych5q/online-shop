package com.group1.onlineshop.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 修改类别请求 DTO（卖家后台管理 · A.1.2-1 类别重命名）。
 * 仅支持改名；不支持改挂父级（需求未要求）。
 */
@Data
public class CategoryUpdateRequest {

    /** 新名称：必填，去空格后 1~50 字 */
    @NotBlank(message = "类别名称必填")
    @Size(max = 50, message = "类别名称不能超过 50 字")
    private String name;
}
