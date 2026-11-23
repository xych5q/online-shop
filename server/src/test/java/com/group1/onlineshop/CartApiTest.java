package com.group1.onlineshop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.group1.onlineshop.entity.Product;
import com.group1.onlineshop.repository.BuyerRepository;
import com.group1.onlineshop.repository.CartItemRepository;
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
 * 购物车接口 HTTP 层测试（D3-2）。
 *
 * <p>需求依据：FR-D10（按账号存储、条目结构、数量可调、加购不校验库存、逐件移除）、
 * G10（重复加购合并累加）、G11（下架/售罄置灰不能结算）、G12（不显示缩略图与总计金额）、
 * FR-D11 / G14 / G15（批量转收藏、转后移出、已收藏去重）、FR-D3（需登录）。
 *
 * <p>覆盖：鉴权、加购与字段契约、合并累加、数量调整与非法值、逐件移除幂等、
 * 下架标注、批量转收藏与去重、账号隔离、结算前校验（缺货与下架两类拦截）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CartApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private CartItemRepository cartItemRepository;

    @Autowired
    private FavoriteRepository favoriteRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private BuyerRepository buyerRepository;

    @BeforeEach
    void cleanUp() {
        cartItemRepository.deleteAll();
        favoriteRepository.deleteAll();
        productRepository.deleteAll();
        buyerRepository.deleteAll();
    }

    // ---------- 鉴权 ----------

    /** 用例 1：不带 token 查购物车 → 401（FR-D3 购物车须登录）。 */
    @Test
    void 未登录查购物车被拒() throws Exception {
        mockMvc.perform(get("/api/cart"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已失效"));
    }

    /** 用例 2：不带 token 加购 → 401。 */
    @Test
    void 未登录加购被拒() throws Exception {
        mockMvc.perform(post("/api/cart")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已失效"));
    }

    // ---------- 加购与字段契约 ----------

    /** 用例 3：加购成功并出现在列表，字段为名称/单价/数量/小计与状态（G12 不含缩略图与总计金额）。 */
    @Test
    void 加购成功并出现在列表() throws Exception {
        Product product = newProduct("购物车商品", "19.90", 5, Product.Status.ON_SALE);
        String token = registerBuyer("carta");

        mockMvc.perform(post("/api/cart").header("X-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + product.getId() + ",\"quantity\":2}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.quantity").value(2));

        mockMvc.perform(get("/api/cart").header("X-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(product.getId()))
                .andExpect(jsonPath("$.items[0].name").value("购物车商品"))
                .andExpect(jsonPath("$.items[0].unitPrice").value(19.90))
                .andExpect(jsonPath("$.items[0].quantity").value(2))
                .andExpect(jsonPath("$.items[0].subtotal").value(39.80))
                .andExpect(jsonPath("$.items[0].status").value("on_sale"))
                .andExpect(jsonPath("$.items[0].available").value(true))
                .andExpect(jsonPath("$.items[0].stockSufficient").value(true))
                .andExpect(jsonPath("$.items[0].imageUrl").doesNotExist())
                .andExpect(jsonPath("$.items[0].totalAmount").doesNotExist());
    }

    /** 用例 4：数量缺省为 1（FR-D10 条目数量，前端可不传）。 */
    @Test
    void 加购数量缺省为一() throws Exception {
        Product product = newProduct("缺省数量", "10.00", 5, Product.Status.ON_SALE);
        String token = registerBuyer("cartb");

        mockMvc.perform(post("/api/cart").header("X-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + product.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(1));
    }

    /** 用例 5：同一商品重复加购合并为一条并累加数量（G10）。 */
    @Test
    void 重复加购合并累加() throws Exception {
        Product product = newProduct("合并商品", "8.00", 10, Product.Status.ON_SALE);
        String token = registerBuyer("cartc");
        addToCart(token, product.getId(), 2);
        addToCart(token, product.getId(), 3);

        assertEquals(1, cartItemRepository.count(), "同一账号同一商品只保留一条");
        mockMvc.perform(get("/api/cart").header("X-Token", token))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].quantity").value(5))
                .andExpect(jsonPath("$.items[0].subtotal").value(40.00));
    }

    /** 用例 6：加购不存在的商品 → 404。 */
    @Test
    void 加购不存在商品被拒() throws Exception {
        String token = registerBuyer("cartd");
        mockMvc.perform(post("/api/cart").header("X-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":999999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("商品不存在"));
    }

    /** 用例 7：加购数量为 0 → 400（数量必须大于 0）。 */
    @Test
    void 加购数量为零被拒() throws Exception {
        Product product = newProduct("非法数量", "5.00", 5, Product.Status.ON_SALE);
        String token = registerBuyer("carte");
        mockMvc.perform(post("/api/cart").header("X-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + product.getId() + ",\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("数量必须大于 0"));
    }

    // ---------- 数量调整 ----------

    /** 用例 8：数量调整覆盖式生效，小计随之变化。 */
    @Test
    void 数量调整生效() throws Exception {
        Product product = newProduct("调量商品", "12.50", 10, Product.Status.ON_SALE);
        String token = registerBuyer("cartf");
        addToCart(token, product.getId(), 1);

        mockMvc.perform(post("/api/cart/" + product.getId() + "/quantity").header("X-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":4}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.quantity").value(4));

        mockMvc.perform(get("/api/cart").header("X-Token", token))
                .andExpect(jsonPath("$.items[0].quantity").value(4))
                .andExpect(jsonPath("$.items[0].subtotal").value(50.00));
    }

    /** 用例 9：调整数量为 0 → 400（移除只能走逐件移除）。 */
    @Test
    void 调整数量为零被拒() throws Exception {
        Product product = newProduct("调零商品", "9.00", 5, Product.Status.ON_SALE);
        String token = registerBuyer("cartg");
        addToCart(token, product.getId(), 1);

        mockMvc.perform(post("/api/cart/" + product.getId() + "/quantity").header("X-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":0}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("数量必须大于 0"));
    }

    /** 用例 10：调整不在购物车中的商品 → 404。 */
    @Test
    void 调整不在购物车的商品被拒() throws Exception {
        Product product = newProduct("不在车", "9.00", 5, Product.Status.ON_SALE);
        String token = registerBuyer("carth");
        mockMvc.perform(post("/api/cart/" + product.getId() + "/quantity").header("X-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":2}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("购物车中没有该商品"));
    }

    // ---------- 逐件移除 ----------

    /** 用例 11：逐件移除生效，重复移除幂等（不报错）。 */
    @Test
    void 逐件移除且幂等() throws Exception {
        Product product = newProduct("移除商品", "6.00", 5, Product.Status.ON_SALE);
        String token = registerBuyer("carti");
        addToCart(token, product.getId(), 1);

        mockMvc.perform(delete("/api/cart/" + product.getId()).header("X-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        mockMvc.perform(get("/api/cart").header("X-Token", token))
                .andExpect(jsonPath("$.total").value(0));

        mockMvc.perform(delete("/api/cart/" + product.getId()).header("X-Token", token))
                .andExpect(status().isOk());
    }

    // ---------- 下架标注 ----------

    /** 用例 12：商品下架后购物车条目保留，标注 off_shelf / 不可结算（G11）。 */
    @Test
    void 下架商品保留并标注() throws Exception {
        Product product = newProduct("下架购物车商品", "29.90", 5, Product.Status.ON_SALE);
        String token = registerBuyer("cartj");
        addToCart(token, product.getId(), 1);

        product.setStatus(Product.Status.OFF_SHELF);
        product.setOffShelfReason("MANUAL");
        productRepository.save(product);

        mockMvc.perform(get("/api/cart").header("X-Token", token))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].status").value("off_shelf"))
                .andExpect(jsonPath("$.items[0].offShelfReason").value("MANUAL"))
                .andExpect(jsonPath("$.items[0].available").value(false));
    }

    /** 用例 13：售罄自动下架同样保留并标注 SOLD_OUT（G11）。 */
    @Test
    void 售罄商品保留并标注() throws Exception {
        Product product = newProduct("售罄购物车商品", "39.90", 0, Product.Status.OFF_SHELF);
        product.setOffShelfReason("SOLD_OUT");
        productRepository.save(product);
        String token = registerBuyer("cartk");
        addToCart(token, product.getId(), 1);

        mockMvc.perform(get("/api/cart").header("X-Token", token))
                .andExpect(jsonPath("$.items[0].offShelfReason").value("SOLD_OUT"))
                .andExpect(jsonPath("$.items[0].available").value(false));
    }

    // ---------- 批量转收藏 ----------

    /** 用例 14：勾选多条一次转收藏，转后移出购物车（FR-D11 / G14）。 */
    @Test
    void 批量转收藏并移出购物车() throws Exception {
        Product p1 = newProduct("转收藏一", "10.00", 5, Product.Status.ON_SALE);
        Product p2 = newProduct("转收藏二", "20.00", 5, Product.Status.ON_SALE);
        String token = registerBuyer("cartl");
        addToCart(token, p1.getId(), 1);
        addToCart(token, p2.getId(), 2);

        mockMvc.perform(post("/api/cart/to-favorites").header("X-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[" + p1.getId() + "," + p2.getId() + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.moved.length()").value(2));

        mockMvc.perform(get("/api/cart").header("X-Token", token))
                .andExpect(jsonPath("$.total").value(0));
        mockMvc.perform(get("/api/favorites").header("X-Token", token))
                .andExpect(jsonPath("$.total").value(2));
    }

    /** 用例 15：已在收藏中的商品再次转入静默去重，不报错、不产生重复收藏（G15）。 */
    @Test
    void 转收藏去重不报错() throws Exception {
        Product product = newProduct("重复转收藏", "15.00", 5, Product.Status.ON_SALE);
        String token = registerBuyer("cartm");
        addToCart(token, product.getId(), 1);
        mockMvc.perform(post("/api/favorites").header("X-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + product.getId() + "}"))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/cart/to-favorites").header("X-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[" + product.getId() + "]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.alreadyFavorited.length()").value(1))
                .andExpect(jsonPath("$.moved.length()").value(0));

        assertEquals(1, favoriteRepository.count(), "已收藏不重复插入");
        mockMvc.perform(get("/api/cart").header("X-Token", token))
                .andExpect(jsonPath("$.total").value(0));
    }

    /** 用例 16：所选商品都不在购物车 → 400。 */
    @Test
    void 转收藏商品不在购物车被拒() throws Exception {
        Product product = newProduct("车外商品", "11.00", 5, Product.Status.ON_SALE);
        String token = registerBuyer("cartn");
        mockMvc.perform(post("/api/cart/to-favorites").header("X-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productIds\":[" + product.getId() + "]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("所选商品不在购物车中"));
    }

    // ---------- 账号隔离 ----------

    /** 用例 17：购物车按账号隔离——两个买家互不可见、互不可改。 */
    @Test
    void 按账号隔离() throws Exception {
        Product p1 = newProduct("A 购物车", "10.00", 5, Product.Status.ON_SALE);
        Product p2 = newProduct("B 购物车", "20.00", 5, Product.Status.ON_SALE);
        String tokenA = registerBuyer("carto");
        String tokenB = registerBuyer("cartp");
        addToCart(tokenA, p1.getId(), 1);
        addToCart(tokenB, p2.getId(), 1);

        mockMvc.perform(get("/api/cart").header("X-Token", tokenA))
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].productId").value(p1.getId()));

        // B 改不动 A 的条目
        mockMvc.perform(post("/api/cart/" + p1.getId() + "/quantity").header("X-Token", tokenB)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"quantity\":9}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/cart").header("X-Token", tokenA))
                .andExpect(jsonPath("$.items[0].quantity").value(1));
    }

    // ---------- 结算前校验 ----------

    /** 用例 18：整车校验通过（pass=true，无 blocked / shortages）。 */
    @Test
    void 结算前校验通过() throws Exception {
        Product product = newProduct("可结算", "19.00", 5, Product.Status.ON_SALE);
        String token = registerBuyer("cartq");
        addToCart(token, product.getId(), 2);

        mockMvc.perform(post("/api/cart/validate").header("X-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pass").value(true))
                .andExpect(jsonPath("$.blocked.length()").value(0))
                .andExpect(jsonPath("$.shortages.length()").value(0));
    }

    /** 用例 19：缺货 + 下架两类拦截同时给出，pass=false（G11 / B.2.3-3）。 */
    @Test
    void 结算前校验拦缺货与下架() throws Exception {
        Product shortStock = newProduct("库存不足", "10.00", 1, Product.Status.ON_SALE);
        Product offShelf = newProduct("已下架", "20.00", 5, Product.Status.OFF_SHELF);
        offShelf.setOffShelfReason("MANUAL");
        productRepository.save(offShelf);
        String token = registerBuyer("cartr");
        addToCart(token, shortStock.getId(), 3);
        addToCart(token, offShelf.getId(), 1);

        mockMvc.perform(post("/api/cart/validate").header("X-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pass").value(false))
                .andExpect(jsonPath("$.shortages.length()").value(1))
                .andExpect(jsonPath("$.shortages[0].productId").value(shortStock.getId()))
                .andExpect(jsonPath("$.shortages[0].required").value(3))
                .andExpect(jsonPath("$.shortages[0].available").value(1))
                .andExpect(jsonPath("$.blocked.length()").value(1))
                .andExpect(jsonPath("$.blocked[0].reason").value("OFF_SHELF"));
    }

    /** 用例 20：空购物车做结算前校验 → 400「购物车为空」。 */
    @Test
    void 空购物车校验被拒() throws Exception {
        String token = registerBuyer("carts");
        mockMvc.perform(post("/api/cart/validate").header("X-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("购物车为空"));
    }

    // ---------- 辅助 ----------

    /** 直接落库一件商品（购物车测试不关心类别与流水） */
    private Product newProduct(String name, String price, int stock, Product.Status status) {
        Product product = new Product();
        product.setName(name);
        product.setDescription("test");
        product.setPrice(new BigDecimal(price));
        product.setStock(stock);
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

    /** 调用加购接口（断言成功） */
    private void addToCart(String token, Long productId, int quantity) throws Exception {
        mockMvc.perform(post("/api/cart").header("X-Token", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + productId + ",\"quantity\":" + quantity + "}"))
                .andExpect(status().isOk());
    }
}
