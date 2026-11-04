package com.group1.onlineshop;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 图片静态映射回归测试（评审整改 · WebConfig）。
 *
 * <p>背景：商品图片落在 {@code app.upload.dir} 目录，接口返回的是
 * {@code /uploads/xxx.png} 相对地址。此前没有资源映射，这类请求一律 404，
 * 买家列表、商品详情图集与卖家后台列表的图片都显示不出来。
 *
 * <p>本类只验证映射本身，不涉及商品业务逻辑。
 */
@SpringBootTest
@AutoConfigureMockMvc
class UploadsResourceTest {

    /** 1x1 透明 PNG */
    private static final byte[] PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==");

    @Autowired
    private MockMvc mvc;

    @Value("${app.upload.dir}")
    private String uploadDir;

    @BeforeEach
    void 准备图片() throws Exception {
        Files.createDirectories(Path.of(uploadDir));
        Files.write(Path.of(uploadDir, "probe.png"), PNG);
    }

    @Test
    @DisplayName("上传目录内的图片可通过 /uploads/ 访问")
    void 图片可访问() throws Exception {
        mvc.perform(get("/uploads/probe.png"))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("上传目录内不存在的文件返回 404")
    void 文件不存在返回404() throws Exception {
        mvc.perform(get("/uploads/not-exists.png"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("默认静态资源未受影响，首页仍可访问")
    void 首页仍可访问() throws Exception {
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk());
    }
}
