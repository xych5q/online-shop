package com.group1.onlineshop.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/** 购买意向。终态三档：成功 / 撤销 / 失败。 */
@Data
@Entity
@Table(name = "intent")
public class Intent {

    public enum Status {
        /** 排队中 */
        QUEUED,
        /** 已进入交易 */
        IN_TRANSACTION,
        /** 终态：交易成功 */
        SUCCESS,
        /** 终态：买家撤销 */
        CANCELLED,
        /** 终态：失败（交易失败或商品下架） */
        FAILED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long productId;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String phone;

    /** 口令码：全局唯一，只在提交成功时返回一次 */
    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private LocalDateTime submittedAt = LocalDateTime.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status = Status.QUEUED;

    /** 失败意向是否已被卖家处置（作废 / 重新排队） */
    private boolean disposed = false;

    /** 重新排队时指向原意向 id */
    private Long requeuedFrom;
}
