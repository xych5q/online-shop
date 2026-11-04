package com.group1.onlineshop.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 新增类别请求 DTO（卖家后台管理 · A.1.2 两级类别）。
 * parentId 为空 = 新建一级类别；非空 = 在某一级类别下新建二级类别。
 */
@Data
public class CategoryCreateRequest {

    /** 类别名称：必填，去空格后 1~50 字 */
    @NotBlank(message = "类别名称必填")
    @Size(max = 50, message = "类别名称不能超过 50 字")
    private String name;

    /** 父类别 id：可选；非空时必须是一级类别（由服务层校验） */
    private Long parentId;
}
