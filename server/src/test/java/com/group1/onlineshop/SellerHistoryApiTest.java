package com.group1.onlineshop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.group1.onlineshop.entity.Buyer;
import com.group1.onlineshop.entity.Intent;
import com.group1.onlineshop.entity.Product;
import com.group1.onlineshop.repository.BuyerRepository;
import com.group1.onlineshop.repository.IntentRepository;
import com.group1.onlineshop.repository.ProductRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 卖家后台「购买历史两维度」HTTP 层测试：
 * 维度一 按客户看（GET /api/seller/customers/{id}/history）；
 * 维度二 按商品看意向购买人（GET /api/seller/products/{id}/intents）。
 * 隐私纪律：两个接口都绝不返回口令码 code。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SellerHistoryApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BuyerRepository buyerRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private IntentRepository intentRepository;

    /** 每个用例前清空业务数据（seller 账号由 DataInitializer 维护，不动） */
    @BeforeEach
    void cleanUp() {
        intentRepository.deleteAll();
        productRepository.deleteAll();
        buyerRepository.deleteAll();
    }

    // ---------- 维度一：按客户看 ----------

    /** 用例 1：不带 X-Token 返回 401。 */
    @Test
    void 按客户看不带token被拒() throws Exception {
        mockMvc.perform(get("/api/seller/customers/1/history"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已失效"));
    }

    /** 用例 2：客户不存在 → 404「客户不存在」。 */
    @Test
    void 按客户看客户不存在() throws Exception {
        mockMvc.perform(get("/api/seller/customers/999/history").header("X-Token", login()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("客户不存在"));
    }

    /** 用例 3：客户有 2 条有效意向 + 1 条归档意向 → 返回 2 条、按提交时间倒序、带商品名、无口令码。 */
    @Test
    void 按客户看倒序且排除归档() throws Exception {
        Buyer buyer = newBuyer("buyer001");
        Product p1 = newProduct("商品A");
        Product p2 = newProduct("商品B");
        newIntent(buyer.getId(), p1.getId(), Intent.Status.SUCCESS, LocalDateTime.now().minusMinutes(10));
        newIntent(buyer.getId(), p2.getId(), Intent.Status.QUEUED, LocalDateTime.now().minusMinutes(5));
        newIntent(buyer.getId(), p1.getId(), Intent.Status.ARCHIVED, LocalDateTime.now().minusMinutes(1));

        mockMvc.perform(get("/api/seller/customers/" + buyer.getId() + "/history").header("X-Token", login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))                       // 归档被排除
                .andExpect(jsonPath("$.items[0].productName").value("商品B"))  // 倒序：最新的在前
                .andExpect(jsonPath("$.items[1].productName").value("商品A"))
                .andExpect(jsonPath("$.items[0].status").value("queued"))
                .andExpect(jsonPath("$.items[0].dealtAt").doesNotExist())
                .andExpect(content().string(not(containsString("\"code\""))));
    }

    // ---------- 维度二：按商品看意向购买人 ----------

    /** 用例 4：商品不存在 → 404「商品不存在」。 */
    @Test
    void 按商品看商品不存在() throws Exception {
        mockMvc.perform(get("/api/seller/products/999/intents").header("X-Token", login()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("商品不存在"));
    }

    /** 用例 5：1 笔交易中 + 2 个排队买家 → 按提交时间正序、position 1/2/3、买家信息齐全。 */
    @Test
    void 按商品看排队位次与买家() throws Exception {
        Buyer b1 = newBuyer("buyer001");
        Buyer b2 = newBuyer("buyer002");
        Buyer b3 = newBuyer("buyer003");
        Product product = newProduct("排队商品");
        // C11 之后首笔提交即进入交易，真实队列不可能出现「三条全 QUEUED」；
        // position 按提交时间正序从 1 起，交易中的那条仍是 position 1。
        newIntent(b1.getId(), product.getId(), Intent.Status.IN_TRANSACTION, LocalDateTime.now().minusMinutes(9));
        newIntent(b2.getId(), product.getId(), Intent.Status.QUEUED, LocalDateTime.now().minusMinutes(6));
        newIntent(b3.getId(), product.getId(), Intent.Status.QUEUED, LocalDateTime.now().minusMinutes(3));

        mockMvc.perform(get("/api/seller/products/" + product.getId() + "/intents").header("X-Token", login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(3))
                .andExpect(jsonPath("$.items[0].username").value("buyer001"))   // 正序
                .andExpect(jsonPath("$.items[0].status").value("in_transaction"))
                .andExpect(jsonPath("$.items[0].position").isEmpty())            // 交易中不占排队位次
                .andExpect(jsonPath("$.items[1].username").value("buyer002"))
                .andExpect(jsonPath("$.items[1].position").value(1))
                .andExpect(jsonPath("$.items[2].username").value("buyer003"))
                .andExpect(jsonPath("$.items[2].position").value(2))
                .andExpect(jsonPath("$.items[0].phone").value("13800000000"));
    }

    /** 用例 6：隐私——按商品看的响应绝不包含口令码。 */
    @Test
    void 按商品看不泄露口令码() throws Exception {
        Buyer buyer = newBuyer("buyer001");
        Product product = newProduct("商品A");
        Intent intent = newIntent(buyer.getId(), product.getId(), Intent.Status.QUEUED, LocalDateTime.now());
        intent.setCode("SECRETCODE");
        intentRepository.save(intent);

        mockMvc.perform(get("/api/seller/products/" + product.getId() + "/intents").header("X-Token", login()))
                .andExpect(status().isOk())
                .andExpect(content().string(not(containsString("\"code\""))))
                .andExpect(content().string(not(containsString("SECRETCODE"))));
    }

    // ---------- 辅助方法 ----------

    /** 登录卖家账号，返回 token */
    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/seller/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"seller123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    /** 直接落库一个买家（测试夹具） */
    private Buyer newBuyer(String username) {
        Buyer buyer = new Buyer();
        buyer.setUsername(username);
        buyer.setPasswordHash("x");
        buyer.setPhone("138" + String.format("%08d", buyerRepository.count() + 1));
        buyer.setDefaultLocation("图书馆");
        return buyerRepository.save(buyer);
    }

    /** 直接落库一个在售商品（测试夹具） */
    private Product newProduct(String name) {
        Product product = new Product();
        product.setName(name);
        product.setPrice(new BigDecimal("19.90"));
        product.setStatus(Product.Status.ON_SALE);
        return productRepository.save(product);
    }

    /** 直接落库一条意向（提交时间显式指定，保证排序确定；name/phone 为提交时快照，均非空） */
    private Intent newIntent(Long buyerId, Long productId, Intent.Status status, LocalDateTime submittedAt) {
        Intent intent = new Intent();
        intent.setBuyerId(buyerId);
        intent.setProductId(productId);
        intent.setName("测试买家");
        intent.setPhone("13800000000");
        intent.setStatus(status);
        intent.setSubmittedAt(submittedAt);
        return intentRepository.save(intent);
    }
}
