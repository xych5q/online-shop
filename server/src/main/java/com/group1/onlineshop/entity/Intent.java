package com.group1.onlineshop.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 购买意向。挑战需求 A 升级后的核心变化：<b>口令码机制整体废弃，意向改绑买家账号</b>。
 *
 * <p>需求依据：
 * <ul>
 *   <li>A.2.1-1 / A.2.1-2 提交意向必须登录，<b>账号本身就代表「我是谁」</b>，口令码整条作废（C6 裁决）</li>
 *   <li>A.2.4-1 / A.2.4-2 基线「卖家后台不显示口令码」「凭码改姓名电话」两条规则<b>一并废弃</b></li>
 *   <li>A.2.1-10 无姓名字段，{@code name} 停止写入（卖家侧显示用户名）</li>
 *   <li>A.2.2-8 买家历史展示「交易结果」三值：待交易 / 成功 / 失败</li>
 *   <li>A.1.3-3 库存归零自动下架时，队列中未成交意向一律置 <b>SOLD_OUT（售罄失效）</b>；补货后原队列不恢复</li>
 *   <li>A.2.4-3 旧系统数据按 <b>B 方案只读归档</b>，标记为 ARCHIVED，不挂账号、不复活队列</li>
 *   <li>A.2.3-9 严格先到先得（FIFO），队首自动进交易，卖家不可跳过队首挑人</li>
 * </ul>
 *
 * <p><b>状态值与买家侧展示的对应</b>：
 * <pre>
 * QUEUED          → 展示「待交易」   排队中
 * IN_TRANSACTION  → 展示「待交易」   已进入交易，尚无结果
 * SUCCESS         → 展示「成功」
 * FAILED          → 展示「失败」
 * SOLD_OUT        → 展示「失败」（售罄失效）
 * ARCHIVED        → 不展示            旧系统只读归档
 * CANCELLED       → 保留枚举值但不再产生（A.2.2-9 买家无取消入口）
 * </pre>
 *
 * <p><b>兼容性说明</b>：本次为增量修改，原有字段一个未删。
 * {@code name} 与 {@code code} 改为<b>可空</b>（不再写入），
 * 避免旧库启动失败，也不影响已有数据的读取与归档。
 * H2 的唯一索引对 NULL 视作互不相同，故多行空 code 不会冲突。
 */
@Data
@Entity
@Table(name = "intent")
public class Intent {

    public enum Status {
        /** 排队中（展示「待交易」） */
        QUEUED,
        /** 已进入交易（展示「待交易」） */
        IN_TRANSACTION,
        /** 终态：交易成功 */
        SUCCESS,
        /** 终态：买家撤销（口径已废弃，A.2.2-9 买家无取消入口，保留仅为兼容旧数据） */
        CANCELLED,
        /** 终态：失败（交易失败或商品下架） */
        FAILED,
        /** 售罄失效：库存归零自动下架时，队列中未成交意向置此值（A.1.3-3） */
        SOLD_OUT,
        /** 旧系统（口令码时代）遗留数据，只读归档，不挂账号、不复活队列（A.2.4-3 B 方案） */
        ARCHIVED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long productId;

    /**
     * 买家账号 id。<b>新意向必填</b>（登录态提交）；
     * <b>旧数据为 null</b>（口令码时代无账号，归档后不追溯挂账，见 A.2.4-3 B 方案）。
     */
    @Column
    private Long buyerId;

    /**
     * 买家姓名。<b>停止写入</b>：A.2.1-10 注册无姓名字段，
     * 卖家线下看到的客户标识是用户名。保留字段仅为兼容旧数据读取。
     */
    @Column
    private String name;

    /** 联系电话：下单时的快照，便于卖家联系与历史查看（取自账号，A.2.1-8 无修正入口） */
    @Column(nullable = false)
    private String phone;

    /**
     * 口令码。<b>机制整体废弃</b>（A.2.1-2 / A.2.4-1）：
     * 不再生成、不再校验、不再返回。保留字段与历史值仅为只读归档备查。
     */
    @Column(unique = true)
    private String code;

    /** 提交时间：既是 FIFO 排队依据，也是历史列表的排序字段（倒序，A.2.2-8） */
    @Column(nullable = false)
    private LocalDateTime submittedAt = LocalDateTime.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.QUEUED;

    /** 失败意向是否已被卖家处置（作废 / 重新排队） */
    private boolean disposed = false;

    /** 重新排队时指向原意向 id（口径：买家自行重新提交即为新记录，此字段不再自动使用） */
    private Long requeuedFrom;

    /**
     * 交易结束时间：卖家标记交易结果的时刻，成功与失败都写入（A.2.2-2「交易时间」）。
     *
     * <p>为什么不复用 {@code Product.closedAt}：成交不再等于下架（A.1.3-4），
     * 库存仍有剩余时商品回到在售、{@code closedAt} 保持 {@code null}，
     * 此时用商品下架时间会取不到值，买家历史里的「交易时间」将为空。
     * 因此交易时间必须是<b>意向自身的字段</b>，由交易服务在标记结果时写入。
     *
     * <p>未成交（排队中、交易中、售罄失效、已归档）为 {@code null}。
     */
    private LocalDateTime dealtAt;
}
