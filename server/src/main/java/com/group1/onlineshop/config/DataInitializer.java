package com.group1.onlineshop.config;

import com.group1.onlineshop.entity.Seller;
import com.group1.onlineshop.repository.SellerRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;
import java.nio.file.Files;
import java.nio.file.Path;

/** 首次启动：创建系统指定的卖家账号 + 建立上传目录。 */
@Slf4j
@Component
@RequiredArgsConstructor
public class DataInitializer implements CommandLineRunner {

    private final SellerRepository sellerRepository;

    @Value("${app.seller.username}")
    private String username;

    @Value("${app.seller.password}")
    private String password;

    @Value("${app.upload.dir}")
    private String uploadDir;

    @Override
    public void run(String... args) throws Exception {
        Files.createDirectories(Path.of(uploadDir));
        if (sellerRepository.findByUsername(username).isEmpty()) {
            Seller seller = new Seller();
            seller.setUsername(username);
            seller.setPasswordHash(new BCryptPasswordEncoder().encode(password));
            sellerRepository.save(seller);
            log.info("已创建卖家账号：{} / {}", username, password);
        }
    }
}
