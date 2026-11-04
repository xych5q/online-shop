package com.group1.onlineshop.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * 静态资源映射：把 {@code /uploads/**} 指向 {@code app.upload.dir} 目录。
 *
 * <p>图片由 {@code ProductService.saveImage} 写入磁盘，接口返回的 imageUrl 形如
 * {@code /uploads/img_xxx.png}。Spring Boot 默认只映射 classpath 下的 static，
 * 这类请求原本直接 404，买家列表、商品详情图集与卖家后台列表的图片全部显示不出来。
 *
 * <p>本类只新增一条映射，不改动默认静态资源处理，也不改变任何既有接口行为。
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final String uploadDir;

    public WebConfig(@Value("${app.upload.dir:./data/uploads}") String uploadDir) {
        this.uploadDir = uploadDir;
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String location = Path.of(uploadDir).toAbsolutePath().normalize().toUri().toString();
        // Path.toUri() 仅在目录已存在时带尾斜杠，缺了会把最后一级当成文件名拼错
        if (!location.endsWith("/")) {
            location = location + "/";
        }
        registry.addResourceHandler("/uploads/**").addResourceLocations(location);
    }
}
