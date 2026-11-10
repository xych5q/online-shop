package com.group1.onlineshop.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 手动调库存请求 DTO（卖家后台管理 · A.1.3-5 卖家手动补/调库存）。
 * stock 为目标库存（非增减量），≥0；每次调整由服务层写 stock_log 流水。
 */
@Data
public class ProductStockUpdateRequest {

    /** 目标库存：必填，≥0（0 表示清零，商品将转售罄） */
    @NotNull(message = "库存必填")
    @Min(value = 0, message = "库存不能为负数")
    private Integer stock;

    /** 调整原因：可选，≤200 字（补货/盘点/损耗） */
    @Size(max = 200, message = "调整原因不能超过 200 字")
    private String reason;
}
