package com.group1.onlineshop;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 在线购物系统 —— 第1组（软件工程实践一）
 * 启动：mvn spring-boot:run　或　mvn package && java -jar target/online-shop.jar
 */
@SpringBootApplication
public class OnlineShopApplication {
    public static void main(String[] args) {
        SpringApplication.run(OnlineShopApplication.class, args);
    }
}
