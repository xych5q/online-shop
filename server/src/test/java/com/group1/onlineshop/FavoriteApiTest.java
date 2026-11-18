package com.group1.onlineshop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.group1.onlineshop.entity.Product;
import com.group1.onlineshop.repository.BuyerRepository;
import com.group1.onlineshop.repository.FavoriteRepository;
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

import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 收藏接口 HTTP 层测试（L3-5）。
 *
 * <p>需求依据：FR-D9（按账号存储、独立列表、下架/售罄保留并标注）、
 * G15（重复收藏去重不报错）、FR-D11（转收藏去重，复用同一 add）。
 *
 * <p>覆盖：鉴权、收藏/去重/取消、不存在商品、下架与售罄标注、账号隔离、收藏时间倒序。
 * 商品状态采用两态口径（L3-1）：售罄 = OFF_SHELF + offShelfReason=SOLD_OUT。
 */
@SpringBootTest
@AutoConfigureMockMvc
class FavoriteApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private FavoriteRepository favoriteRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private BuyerRepository buyerRepository;

    @BeforeEach
    void cleanUp() {
        favoriteRepository.deleteAll();
        productRepository.deleteAll();
        buyerRepository.deleteAll();
    }

    // ---------- 鉴权 ----------

    /** 用例 1：不带 token 查收藏列表 → 401。 */
    @Test
    void 未登录查列表被拒() throws Exception {
        mockMvc.perform(get("/api/favorites"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已失效"));
    }

    /** 用例 2：不带 token 收藏商品 → 401。 */
    @Test
    void 未登录收藏被拒() throws Exception {
        mockMvc.perform(post("/api/favorites")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已失效"));
    }

    // ---------- 收藏 / 去重 / 取消 ----------

    /** 用例 3：收藏成功并出现在列表，字段齐全（商品名/价格/状态/可购买标记/收藏时间）。 */
    @Test
    void 收藏成功并出现在列表() throws Exception {
        Product product = newProduct("收藏商品", "19.90", Product.Status.ON_SALE);
        String token = registerBuyer("fava");

        mockMvc.perform(post("/api/favorites").header("X-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + product.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        mockMvc.perform(get("/api/favorites").header("X-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(product.getId()))
                .andExpect(jsonPath("$.items[0].name").value("收藏商品"))
                .andExpect(jsonPath("$.items[0].price").value(19.90))
                .andExpect(jsonPath("$.items[0].status").value("on_sale"))
                .andExpect(jsonPath("$.items[0].offShelfReason").value(nullValue()))
                .andExpect(jsonPath("$.items[0].available").value(true))
                .andExpect(jsonPath("$.items[0].imageUrl").value(nullValue()))
                .andExpect(jsonPath("$.items[0].favoritedAt").isNotEmpty());
    }

    /** 用例 4：重复收藏去重——第二次仍成功，但只保留一条（G15）。 */
    @Test
    void 重复收藏去重() throws Exception {
        Product product = newProduct("去重商品", "9.90", Product.Status.ON_SALE);
        String token = registerBuyer("favb");

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/api/favorites").header("X-Token", token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"productId\":" + product.getId() + "}"))
                    .andExpect(status().isOk());
        }

        assertEquals(1, favoriteRepository.count(), "同一账号同一商品只保留一条");
        mockMvc.perform(get("/api/favorites").header("X-Token", token))
                .andExpect(jsonPath("$.total").value(1));
    }

    /** 用例 5：收藏不存在的商品 → 404。 */
    @Test
    void 收藏不存在商品被拒() throws Exception {
        String token = registerBuyer("favc");
        mockMvc.perform(post("/api/favorites").header("X-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":999999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("商品不存在"));
    }

    /** 用例 6：缺少 productId → 400。 */
    @Test
    void 缺少商品id被拒() throws Exception {
        String token = registerBuyer("favd");
        mockMvc.perform(post("/api/favorites").header("X-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("请选择要收藏的商品"));
    }

    /** 用例 7：取消收藏生效，且重复取消幂等（不报错）。 */
    @Test
    void 取消收藏且幂等() throws Exception {
        Product product = newProduct("取消商品", "5.00", Product.Status.ON_SALE);
        String token = registerBuyer("fave");
        mockMvc.perform(post("/api/favorites").header("X-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + product.getId() + "}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/favorites/" + product.getId()).header("X-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        mockMvc.perform(get("/api/favorites").header("X-Token", token))
                .andExpect(jsonPath("$.total").value(0));

        // 幂等：再次取消仍返回成功
        mockMvc.perform(delete("/api/favorites/" + product.getId()).header("X-Token", token))
                .andExpect(status().isOk());
    }

    // ---------- 下架 / 售罄标注 ----------

    /** 用例 8：商品手动下架后收藏保留，并标注 off_shelf / MANUAL / 不可购买。 */
    @Test
    void 下架商品保留并标注() throws Exception {
        Product product = newProduct("下架商品", "29.90", Product.Status.ON_SALE);
        String token = registerBuyer("favf");
        favorite(token, product.getId());

        product.setStatus(Product.Status.OFF_SHELF);
        product.setOffShelfReason("MANUAL");
        productRepository.save(product);

        mockMvc.perform(get("/api/favorites").header("X-Token", token))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].status").value("off_shelf"))
                .andExpect(jsonPath("$.items[0].offShelfReason").value("MANUAL"))
                .andExpect(jsonPath("$.items[0].available").value(false));
    }

    /** 用例 9：商品售罄自动下架后收藏保留，标注 SOLD_OUT。 */
    @Test
    void 售罄商品保留并标注() throws Exception {
        Product product = newProduct("售罄商品", "39.90", Product.Status.ON_SALE);
        String token = registerBuyer("favg");
        favorite(token, product.getId());

        product.setStatus(Product.Status.OFF_SHELF);
        product.setOffShelfReason("SOLD_OUT");
        productRepository.save(product);

        mockMvc.perform(get("/api/favorites").header("X-Token", token))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].offShelfReason").value("SOLD_OUT"))
                .andExpect(jsonPath("$.items[0].available").value(false));
    }

    // ---------- 账号隔离 / 排序 ----------

    /** 用例 10：收藏按账号隔离——两个买家互相看不到对方的收藏。 */
    @Test
    void 按账号隔离() throws Exception {
        Product p1 = newProduct("A 的商品", "10.00", Product.Status.ON_SALE);
        Product p2 = newProduct("B 的商品", "20.00", Product.Status.ON_SALE);
        String tokenA = registerBuyer("favh");
        String tokenB = registerBuyer("favi");
        favorite(tokenA, p1.getId());
        favorite(tokenB, p2.getId());

        mockMvc.perform(get("/api/favorites").header("X-Token", tokenA))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(p1.getId()));
        mockMvc.perform(get("/api/favorites").header("X-Token", tokenB))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(p2.getId()));
    }

    /** 用例 11：收藏列表按收藏时间倒序（后收藏的排前面）。 */
    @Test
    void 按收藏时间倒序() throws Exception {
        Product p1 = newProduct("先收藏", "10.00", Product.Status.ON_SALE);
        Product p2 = newProduct("后收藏", "20.00", Product.Status.ON_SALE);
        String token = registerBuyer("favj");
        favorite(token, p1.getId());
        favorite(token, p2.getId());

        mockMvc.perform(get("/api/favorites").header("X-Token", token))
                .andExpect(jsonPath("$.items[0].productId").value(p2.getId()))
                .andExpect(jsonPath("$.items[1].productId").value(p1.getId()));
    }

    // ---------- 辅助 ----------

    /** 直接落库一件商品（收藏测试不关心类别与流水） */
    private Product newProduct(String name, String price, Product.Status status) {
        Product product = new Product();
        product.setName(name);
        product.setDescription("test");
        product.setPrice(new BigDecimal(price));
        product.setStock(5);
        product.setStatus(status);
        return productRepository.save(product);
    }

    /** 注册并登录一个买家，返回 token（用户名带时间戳避免跨测试重名） */
    private String registerBuyer(String prefix) throws Exception {
        String username = prefix + "_" + (System.currentTimeMillis() % 10000000L);
        String password = "abcd1234";
        mockMvc.perform(post("/api/buyers/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password
                                + "\",\"phone\":\"13800000001\",\"phoneConfirm\":\"13800000001\"}"))
                .andExpect(status().isOk());
        MvcResult result = mockMvc.perform(post("/api/buyers/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    /** 调用收藏接口（断言成功） */
    private void favorite(String token, Long productId) throws Exception {
        mockMvc.perform(post("/api/favorites").header("X-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + productId + "}"))
                .andExpect(status().isOk());
    }
}
