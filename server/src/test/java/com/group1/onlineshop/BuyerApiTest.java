package com.group1.onlineshop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.group1.onlineshop.entity.Category;
import com.group1.onlineshop.entity.Product;
import com.group1.onlineshop.repository.CategoryRepository;
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
import org.springframework.test.web.servlet.ResultActions;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 买家端接口与部分卖家端接口的 HTTP 层测试（课程 16 组用例中的第 1~11 组）。
 * 用 MockMvc 走真实 Spring MVC 链路，统一异常返回经 GlobalExceptionHandler 后断言。
 * 卖家端接口用例（1~5、12）依赖王振涛的 SellerController，其已随仓库推送，故直接启用。
 */
@SpringBootTest
@AutoConfigureMockMvc
class BuyerApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private IntentRepository intentRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    /** 发布用例共用的二级类别 id（多商品发布要求商品必挂二级类别） */
    private Long categoryId;

    /** 每个用例前清空业务数据（H2 文件库跨测试持久化，必须清理；seller 账号由 DataInitializer 维护，不动） */
    @BeforeEach
    void cleanUp() {
        intentRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        Category root = new Category();
        root.setName("测试分类");
        root.setParentId(null);
        root = categoryRepository.save(root);
        Category child = new Category();
        child.setName("测试子类");
        child.setParentId(root.getId());
        categoryId = categoryRepository.save(child).getId();
    }

    /** 用例 1：错误密码登录返回 401「用户名或密码错误」。 */
    @Test
    void 错误密码登录返回401() throws Exception {
        mockMvc.perform(post("/api/seller/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong-password\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("用户名或密码错误"));
    }

    /** 用例 2：不带 X-Token 访问 /api/seller/overview 返回 401「未登录或登录已失效」。 */
    @Test
    void 不带XToken访问卖家总览返回401() throws Exception {
        mockMvc.perform(get("/api/seller/overview"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已失效"));
    }

    /** 用例 3：发布价格为 -1 的商品返回 400「价格必须为大于 0 的数字」（多商品发布：DTO 注解校验拦截）。 */
    @Test
    void 发布负价格商品返回400() throws Exception {
        String token = login();
        publish(token, "测试商品", "-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("价格必须为大于 0 的数字"));
    }

    /** 用例 4：发布成功且价格保留两位小数（接口返回 19.90、数据库存 19.90）。多商品发布下图片可不传。 */
    @Test
    void 发布成功且价格保留两位小数() throws Exception {
        String token = login();
        publish(token, "测试商品", "19.9")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        // 数据库：价格必须为 19.90（两位小数）
        Product saved = productRepository.findAll().get(0);
        assertEquals(0, new BigDecimal("19.90").compareTo(saved.getPrice()));
        // 接口：/api/product 序列化出的价格为 19.90
        mockMvc.perform(get("/api/product"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.price").value(19.9))
                .andExpect(content().string(containsString("\"price\":19.90")));
    }

    /** 用例 5（改）：多商品发布——已有在售商品时再次发布成功 200（A.1.1-1 取消「一次只能卖一件」单品约束）。 */
    @Test
    void 已有在售商品再次发布成功() throws Exception {
        String token = login();
        publish(token, "商品A", "19.9").andExpect(status().isOk());
        publish(token, "商品B", "29.9")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }

    /** 用例 6（迭代2 · FR-D3 / W-3）：未登录提交意向返回 401「未登录或登录已失效」，不存在匿名通道。 */
    @Test
    void 未登录提交意向返回401() throws Exception {
        mockMvc.perform(post("/api/intents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":1}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已失效"));
    }

    /** 用例 7（迭代2 · W-3）：买家登录态提交意向成功，只回 intent 四字段，不回口令码、不回位次（C6 / A.2.2-6）。 */
    @Test
    void 登录态提交意向成功且不含口令码() throws Exception {
        Product product = createOnSaleProduct();
        String buyerToken = registerBuyer("buyer01");
        mockMvc.perform(post("/api/intents")
                        .header("X-Token", buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + product.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent.intentId").exists())
                .andExpect(jsonPath("$.intent.productId").value(product.getId()))
                .andExpect(jsonPath("$.intent.status").exists())
                .andExpect(jsonPath("$.intent.submittedAt").exists())
                .andExpect(jsonPath("$.intent.code").doesNotExist())
                .andExpect(jsonPath("$.intent.position").doesNotExist());
    }

    /** 用例 8（迭代2 · W-3）：商品不存在提交意向返回 400「商品不存在」（多商品场景必须指定 productId）。 */
    @Test
    void 商品不存在时提交意向返回400() throws Exception {
        String buyerToken = registerBuyer("buyer02");
        mockMvc.perform(post("/api/intents")
                        .header("X-Token", buyerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":999999}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("商品不存在"));
    }

    /** 登录卖家账号（admin / seller123，DataInitializer 首次启动创建），返回 token */
    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/seller/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"seller123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    /** 以卖家身份发布商品（返回 ResultActions 便于继续断言）。多商品发布契约：必挂二级类别，图片可不传。 */
    private ResultActions publish(String token, String name, String price) throws Exception {
        return mockMvc.perform(post("/api/seller/products")
                .header("X-Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"description\":\"测试描述\",\"price\":" + price
                        + ",\"categoryId\":" + categoryId + ",\"stock\":5}"));
    }

    /** 直接落库一个在售商品（买家侧用例的测试数据准备） */
    private Product createOnSaleProduct() {
        Product product = new Product();
        product.setName("测试商品");
        product.setDescription("测试描述");
        product.setPrice(new BigDecimal("99.90"));
        product.setStock(5);
        product.setStatus(Product.Status.ON_SALE);
        return productRepository.save(product);
    }

    /** 注册并登录买家账号，返回 X-Token（FR-D1 / FR-D2）；用户名带时间戳，避免 H2 文件库跨轮次残留导致重名。 */
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
}
