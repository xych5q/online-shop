package com.group1.onlineshop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.group1.onlineshop.entity.Buyer;
import com.group1.onlineshop.repository.BuyerRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 卖家后台「客户列表」HTTP 层测试。
 * 规则：需卖家鉴权；按注册时间倒序；只返回 {id,username,phone,defaultLocation,createdAt}，
 * 绝不返回 passwordHash 与登录 token（隐私）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SellerCustomerApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BuyerRepository buyerRepository;

    /** 每个用例前清空买家表（H2 文件库跨测试持久化；seller 账号由 DataInitializer 维护，不动） */
    @BeforeEach
    void cleanUp() {
        buyerRepository.deleteAll();
    }

    /** 用例 1：不带 X-Token 访问客户列表返回 401。 */
    @Test
    void 不带token被拒() throws Exception {
        mockMvc.perform(get("/api/seller/customers"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已失效"));
    }

    /** 用例 2：无买家时返回空列表。 */
    @Test
    void 空列表() throws Exception {
        mockMvc.perform(get("/api/seller/customers").header("X-Token", login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.total").value(0))
                .andExpect(jsonPath("$.items").isEmpty());
    }

    /** 用例 3：注册 3 个买家，列表按注册时间倒序（最后注册的排最前），字段齐全。 */
    @Test
    void 按注册时间倒序且字段齐全() throws Exception {
        // 显式指定 createdAt 落库：真实注册的时间可能落在同一毫秒，
        // 而 findAllByOrderByCreatedAtDesc 没有 id 次序兜底，固定顺序断言会偶发失败。
        LocalDateTime base = LocalDateTime.now().minusHours(1);
        newBuyer("buyer001", "13800000001", "图书馆", base);
        newBuyer("buyer002", "13800000002", "教学楼A", base.plusMinutes(1));
        newBuyer("buyer003", "13800000003", "食堂", base.plusMinutes(2));

        mockMvc.perform(get("/api/seller/customers").header("X-Token", login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                // 倒序：最后注册的 buyer003 排最前
                .andExpect(jsonPath("$.items[0].username").value("buyer003"))
                .andExpect(jsonPath("$.items[1].username").value("buyer002"))
                .andExpect(jsonPath("$.items[2].username").value("buyer001"))
                // 字段齐全：用户名 / 电话 / 默认交易地点 / 注册时间
                .andExpect(jsonPath("$.items[0].phone").value("13800000003"))
                .andExpect(jsonPath("$.items[0].defaultLocation").value("食堂"))
                .andExpect(jsonPath("$.items[0].createdAt").isNotEmpty());
    }

    /** 用例 4：隐私——响应绝不包含 passwordHash 与登录 token。 */
    @Test
    void 不泄露密码哈希与token() throws Exception {
        register("buyer001", "13800000001", "图书馆");
        mockMvc.perform(get("/api/seller/customers").header("X-Token", login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].passwordHash").doesNotExist())
                .andExpect(jsonPath("$.items[0].token").doesNotExist());
    }

    /** 直接落库买家并显式指定注册时间（仅用于排序用例，不参与登录） */
    private void newBuyer(String username, String phone, String defaultLocation, LocalDateTime createdAt) {
        Buyer buyer = new Buyer();
        buyer.setUsername(username);
        buyer.setPasswordHash("not-used-in-this-test");
        buyer.setPhone(phone);
        buyer.setDefaultLocation(defaultLocation);
        buyer.setCreatedAt(createdAt);
        buyerRepository.save(buyer);
    }

    /** 登录卖家账号（admin / seller123），返回 token */
    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/seller/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"seller123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    /** 通过买家注册接口造数据（真实链路，保证 createdAt 自然递增） */
    private void register(String username, String phone, String defaultLocation) throws Exception {
        mockMvc.perform(post("/api/buyers/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(java.util.Map.of(
                                "username", username,
                                "password", "pass1234",
                                "phone", phone,
                                "phoneConfirm", phone,
                                "defaultLocation", defaultLocation))))
                .andExpect(status().isOk());
    }
}
