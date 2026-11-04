package com.group1.onlineshop.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 买家账号（挑战需求 A · A.2.1：允许客户自由注册）。
 *
 * <p>注册字段：用户名、密码、电话、默认交易地点（选填）。<b>没有姓名字段</b>（A.2.1-10）。
 *
 * <p>规则依据：
 * <ul>
 *   <li>A.2.1-3 用户名是唯一标识，注册时校验唯一性；<b>手机号只要求必填、不唯一</b>（一个手机号可注册多个账号）</li>
 *   <li>A.2.1-6 密码长度不少于 8 位、加密存储；<b>强度细则由本组自定</b>（≥8 且至少含字母与数字）</li>
 *   <li>A.2.1-11 用户名长度/字符集/大小写规则由本组自定：3–20 位，字母/数字/下划线，唯一性校验忽略大小写</li>
 *   <li>A.2.1-4 / A.2.1-5 默认交易地点选填、自由文本、只填一个、不参与任何交易流程校验</li>
 *   <li>A.2.1-7 无手机验证码</li>
 *   <li>A.2.1-8 / A.2.1-9 <b>不做</b>修改资料、找回密码、重置密码、注销账号</li>
 * </ul>
 *
 * <p>注意：注册登录是 A 覆盖基线的核心变更（C5 裁决）——基线「免注册 + 口令码」
 * 整条作废，提交购买意向必须处于登录态（A.2.1-1 / A.2.1-2）。
 */
@Data
@Entity
@Table(name = "buyer")
public class Buyer {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * 登录账号名。唯一性校验<b>忽略大小写</b>，显示时保留用户输入的原样。
     * 唯一索引本身区分大小写，故「忽略大小写」在应用层用 lower(username) 查重。
     */
    @Column(nullable = false, unique = true, length = 20)
    private String username;

    /** BCrypt 哈希后的密码 */
    @Column(nullable = false, length = 100)
    private String passwordHash;

    /** 交易联系电话。<b>必填但不唯一</b>（A.2.1-3）。无修正入口（A.2.1-8，见风险 R-A） */
    @Column(nullable = false, length = 20)
    private String phone;

    /** 默认交易地点：选填、自由文本、不参与交易校验（A.2.1-4） */
    @Column(length = 200)
    private String defaultLocation;

    /** 注册时间；卖家侧客户列表按此倒序（A.2.3-1） */
    @Column(nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    /** 登录态令牌；登录后刷新，旧令牌立即失效（沿用 Seller 的做法） */
    @Column(length = 100)
    private String token;
}
