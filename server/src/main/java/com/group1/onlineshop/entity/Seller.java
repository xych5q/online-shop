package com.group1.onlineshop.entity;

import jakarta.persistence.*;
import lombok.Data;

/** 卖家账号：系统直接指定，无注册流程。 */
@Data
@Entity
@Table(name = "seller")
public class Seller {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    /** BCrypt 哈希后的密码 */
    @Column(nullable = false)
    private String passwordHash;

    /** 登录态令牌；登录后刷新，旧令牌立即失效 */
    private String token;
}
