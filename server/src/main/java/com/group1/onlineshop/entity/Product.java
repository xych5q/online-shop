package com.group1.onlineshop.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品。挑战需求 A 升级后状态机为 <b>4 态</b>：在售 / 已冻结 / 已下架 / 售罄。
 *
 * <p><b>状态转移与触发条件</b>（老师第 1 次点评技术意见 1、3、5）：
 * <pre>
 * ON_SALE        --[存在排队中意向且商品在售 → 队首自动进入交易，交易服务同一事务内改状态]-->  FROZEN(auto)
 * ON_SALE        --[卖家手动冻结]------------------------------------------------------>  FROZEN(manual)
 * FROZEN(manual) --[卖家手动解冻]------------------------------------------------------>  ON_SALE
 * FROZEN(auto)   --[卖家标记交易结果 result=SUCCESS]------------------------------------>  OFF_SHELF(DEAL_SUCCESS)
 * FROZEN(auto)   --[卖家标记 result=FAILURE 且队列为空]（回退）-------------------------->  ON_SALE
 * ON_SALE        --[库存扣减后 stock == 0]--------------------------------------------->  SOLD_OUT
 * SOLD_OUT       --[卖家手动补库存且 stock ≥ 1]（回退，A.1.3-5）------------------------->  ON_SALE
 * </pre>
 *
 * <p>需求依据：
 * <ul>
 *   <li>A.1.1-1 可同时发布多个商品，多件在售、多笔交易并行（基线「一次只能卖一件」作废）</li>
 *   <li>A.1.1-3 发布后名称/描述/图片/价格/类别<b>无修改入口</b>，<b>仅库存可手动调</b>，商品<b>可改挂类别</b></li>
 *   <li>A.1.2-2 必挂二级类别，故增加 categoryId</li>
 *   <li>A.1.3-1 库存发布时要求 ≥1</li>
 *   <li>A.1.5-1 / A.1.5-4 富文本描述（可内嵌图片与视频），描述可为空</li>
 * </ul>
 *
 * <p><b>兼容性说明</b>：本次为增量修改，<b>原有字段一个未删</b>。
 * 新增的 stock / categoryId 设为<b>可空</b>，原因是 H2 使用 {@code ddl-auto: update}，
 * 只加列不回填数据；对已有商品行加 NOT NULL 会导致旧库启动失败。
 * 必填约束改由应用层在发布商品时校验（stock ≥ 1、categoryId 非空）。
 */
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
        OFF_SHELF,
        /** 售罄：库存归零自动触发（A.1.3）；补库存可回到在售（A.1.3-5） */
        SOLD_OUT
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /** 简要描述（沿用） */
    @Column(length = 2000)
    private String description;

    /**
     * 富文本描述（A.1.5-1）：可内嵌图片与视频，允许为空（A.1.5-4）。
     * 长度上限按 HTML 片段估算取 8000。
     */
    @Column(length = 8000)
    private String richDescription;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    /**
     * 库存数量（A.1.3）。发布时要求 ≥1，由应用层校验。
     * <b>可空</b>：为兼容已有数据，旧商品由迁移脚本回填为 1。
     */
    @Column
    private Integer stock;

    /**
     * 所属二级类别 id（A.1.2-2 必填）。<b>可空</b>：为兼容已有数据，
     * 旧商品由迁移脚本挂到「未分类 / 历史商品」下。必填由应用层校验。
     */
    @Column
    private Long categoryId;

    /** 图片文件名，存于 app.upload.dir；为空时前端显示占位图（旧单图字段，保留不动） */
    private String image;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ON_SALE;

    /** 下架原因：SOLD_OUT（售罄）/ DEAL_SUCCESS（交易成功）/ MANUAL（卖家手动）。便于历史区分（点评技术意见 4） */
    @Column(length = 20)
    private String offShelfReason;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime frozenAt;

    /** manual = 卖家手动冻结；auto = 进入交易自动冻结 */
    private String frozenBy;

    /** 下架时间（进入历史的时间） */
    private LocalDateTime closedAt;
}
