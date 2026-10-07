package com.group1.onlineshop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.group1.onlineshop.entity.Product;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
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

    /** 每个用例前清空业务数据（H2 文件库跨测试持久化，必须清理；seller 账号由 DataInitializer 维护，不动） */
    @BeforeEach
    void cleanUp() {
        intentRepository.deleteAll();
        productRepository.deleteAll();
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

    /** 用例 3：发布价格为 -1 的商品返回 400「价格必须为大于 0 的数字」。 */
    @Test
    void 发布负价格商品返回400() throws Exception {
        String token = login();
        publish(token, "测试商品", "-1")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("价格必须为大于 0 的数字"));
    }

    /** 用例 4：发布成功且价格保留两位小数（接口返回 19.90、数据库存 19.90）。 */
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

    /** 用例 5：已有在售商品时再次发布返回 400「已有商品在售或交易中，需先下架」。 */
    @Test
    void 已有在售商品再次发布返回400() throws Exception {
        String token = login();
        publish(token, "商品A", "19.9").andExpect(status().isOk());
        publish(token, "商品B", "29.9")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("已有商品在售或交易中，需先下架"));
    }

    /** 用例 6：三个买家依次入队，位次分别为 1、2、3。 */
    @Test
    void 三个买家依次入队位次分别为123() throws Exception {
        createOnSaleProduct();
        String code1 = submitIntent("张三", "13800000001");
        String code2 = submitIntent("李四", "13800000002");
        String code3 = submitIntent("王五", "13800000003");
        mockMvc.perform(get("/api/intents/lookup/" + code1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent.position").value(1))
                .andExpect(jsonPath("$.intent.status").value("queued"))
                .andExpect(jsonPath("$.intent.inTransaction").value(false));
        mockMvc.perform(get("/api/intents/lookup/" + code2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent.position").value(2));
        mockMvc.perform(get("/api/intents/lookup/" + code3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent.position").value(3));
    }

    /** 用例 7：姓名或电话为空提交意向返回 400「姓名与联系电话均为必填」。 */
    @Test
    void 姓名或电话为空提交意向返回400() throws Exception {
        createOnSaleProduct();
        // 姓名为空白
        mockMvc.perform(post("/api/intents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"   \",\"phone\":\"13800000001\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("姓名与联系电话均为必填"));
        // 电话为空
        mockMvc.perform(post("/api/intents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"张三\",\"phone\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("姓名与联系电话均为必填"));
    }

    /** 用例 8：无效口令码查询返回 404「口令码无效或已失效」。 */
    @Test
    void 无效口令码查询返回404() throws Exception {
        mockMvc.perform(get("/api/intents/lookup/NOTEXIST"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("口令码无效或已失效"));
    }

    /** 用例 9：修改电话后排队位次不变（只改姓名电话，不动 submittedAt）。 */
    @Test
    void 修改电话后位次不变() throws Exception {
        createOnSaleProduct();
        String code1 = submitIntent("张三", "13800000001");
        String code2 = submitIntent("李四", "13800000002");
        String code3 = submitIntent("王五", "13800000003");
        mockMvc.perform(put("/api/intents/lookup/" + code2)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"李四\",\"phone\":\"13900000009\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        // 李四电话已更新、位次仍为 2；王五位次不受影响
        mockMvc.perform(get("/api/intents/lookup/" + code2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent.position").value(2))
                .andExpect(jsonPath("$.intent.phone").value("13900000009"));
        mockMvc.perform(get("/api/intents/lookup/" + code3))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent.position").value(3));
    }

    /** 用例 10：撤销意向后该口令码查询返回 404，且队列自动前移。 */
    @Test
    void 撤销后该口令码查询返回404() throws Exception {
        createOnSaleProduct();
        String code1 = submitIntent("张三", "13800000001");
        String code2 = submitIntent("李四", "13800000002");
        mockMvc.perform(post("/api/intents/lookup/" + code1 + "/cancel"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        // 已撤销（终态）→ 该口令码立即失效
        mockMvc.perform(get("/api/intents/lookup/" + code1))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("口令码无效或已失效"));
        // 撤销后队列前移：李四位次变为 1
        mockMvc.perform(get("/api/intents/lookup/" + code2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent.position").value(1));
    }

    /** 用例 11：商品冻结后提交意向返回 400「商品交易中，暂停接收新意向」。 */
    @Test
    void 商品冻结后提交意向返回400() throws Exception {
        Product product = createOnSaleProduct();
        // 模拟卖家手动冻结
        product.setStatus(Product.Status.FROZEN);
        product.setFrozenBy("manual");
        product.setFrozenAt(LocalDateTime.now());
        productRepository.save(product);
        mockMvc.perform(post("/api/intents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"张三\",\"phone\":\"13800000001\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("商品交易中，暂停接收新意向"));
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

    /** 以卖家身份发布商品（返回 ResultActions 便于继续断言） */
    private ResultActions publish(String token, String name, String price) throws Exception {
        return mockMvc.perform(post("/api/seller/products")
                .header("X-Token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\":\"" + name + "\",\"description\":\"测试描述\",\"price\":" + price + "}"));
    }

    /** 直接落库一个在售商品（买家侧用例的测试数据准备） */
    private Product createOnSaleProduct() {
        Product product = new Product();
        product.setName("测试商品");
        product.setDescription("测试描述");
        product.setPrice(new BigDecimal("99.90"));
        product.setStatus(Product.Status.ON_SALE);
        return productRepository.save(product);
    }

    /** 通过买家接口提交意向，返回口令码 */
    private String submitIntent(String name, String phone) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/intents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name, "phone", phone))))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("code").asText();
    }
}
