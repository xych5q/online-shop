package com.group1.onlineshop.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.UpdateTimestamp;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品。挑战需求 B 升级后状态机为 <b>2 态</b>：在售 / 已下架。
 *
 * <p><b>状态转移与触发条件</b>（SRS 4.1 商品状态机、FR-S4）：
 * <pre>
 * ON_SALE   --[下单扣减后库存归零，售罄自动下架，offShelfReason=SOLD_OUT]-->  OFF_SHELF
 * ON_SALE   --[卖家手动下架，offShelfReason=MANUAL]------------------------>  OFF_SHELF
 * OFF_SHELF --[卖家补货或调整库存，且库存≥1]--------------------------------->  ON_SALE
 * OFF_SHELF --[订单取消回补后按最终库存判断，仅售罄自动下架的恢复]---------->  ON_SALE
 * </pre>
 *
 * <p><b>与迭代 2 的差异（废止能力登记）</b>：迭代 2 曾为 4 态（在售/冻结/已下架/售罄）。
 * 本次按 SRS FR-S4「商品只有在售与已下架两态，不提供冻结与解冻」及状态机 v3，
 * 删除 {@code FROZEN}、{@code SOLD_OUT} 两个枚举值与冻结字段 {@code frozenBy}、{@code frozenAt}；
 * 冻结 / 解冻 / 自动冻结 / 售罄独立态整套机制废止。售罄不再单独成态，改为「已下架」+
 * {@code offShelfReason=SOLD_OUT} 表达；因此 {@code offShelfReason} 保留，供取消回补时
 * 区分「售罄自动下架（可恢复）」与「卖家手动下架（不恢复）」。{@code closedAt} 作为下架时间保留。
 *
 * <p>需求依据：
 * <ul>
 *   <li>FR-S4 商品两态与上下架，不提供冻结与解冻</li>
 *   <li>FR-C4 库存归零自动下架、补货可重新上架、下架不牵连进行中订单</li>
 *   <li>FR-S8 历史商品按最后成交时间倒序</li>
 * </ul>
 *
 * <p><b>兼容性</b>：沿用迭代 2 表结构，{@code stock} / {@code categoryId} 保持可空，
 * 由应用层校验必填，保证在旧库上原地启动。
 */
@Data
@Entity
@Table(name = "product")
public class Product {

    public enum Status {
        /** 在售 */
        ON_SALE,
        /** 已下架（进历史；售罄与手动下架均为此态，以 offShelfReason 区分） */
        OFF_SHELF
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100)
    private String name;

    /** 简要描述（发布后不可改） */
    @Column(length = 2000)
    private String description;

    /** 富文本描述（可内嵌图片与视频，允许为空，发布后不可改） */
    @Column(length = 8000)
    private String richDescription;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    /** 库存数量。发布时要求 ≥1，由应用层校验；为兼容旧数据保持可空 */
    @Column
    private Integer stock;

    /** 所属二级类别 id。必填由应用层校验；为兼容旧数据保持可空 */
    @Column
    private Long categoryId;

    /** 图片文件名，旧单图字段保留不动 */
    private String image;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.ON_SALE;

    /** 下架原因：SOLD_OUT（售罄自动下架）/ MANUAL（卖家手动下架）。取消回补据此判断是否恢复在售 */
    @Column(length = 20)
    private String offShelfReason;

    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 最后修改时间（调库存 / 改挂类别 / 上下架时由 Hibernate 自动刷新） */
    @UpdateTimestamp
    private LocalDateTime updatedAt;

    /** 下架时间（进入历史的时间） */
    private LocalDateTime closedAt;
}
