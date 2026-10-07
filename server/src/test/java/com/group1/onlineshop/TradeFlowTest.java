package com.group1.onlineshop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.group1.onlineshop.entity.Intent;
import com.group1.onlineshop.entity.Product;
import com.group1.onlineshop.repository.IntentRepository;
import com.group1.onlineshop.repository.ProductRepository;
import com.group1.onlineshop.service.IntentService;
import com.group1.onlineshop.service.TradeService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 交易流转核心测试（课程 16 组用例中的第 12~16 组）。
 * 交易规则（进入交易 / 标记结果 / 处置失败意向）直接调用本模块的 TradeService 验证
 * （卖家控制器当前仍走 ProductService 中的临时实现，规则相同但归属队友文件，不改动）；
 * 涉及 HTTP 的对外行为（手动解冻拦截、口令码查询、历史详情）通过 MockMvc 走真实链路验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TradeFlowTest {

    @Autowired
    private TradeService tradeService;

    @Autowired
    private IntentService intentService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private IntentRepository intentRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** 每个用例前清空业务数据（H2 文件库跨测试持久化，必须清理；seller 账号由 DataInitializer 维护，不动） */
    @BeforeEach
    void cleanUp() {
        intentRepository.deleteAll();
        productRepository.deleteAll();
    }

    /** 用例 12：交易中（自动冻结 frozenBy=auto）手动解冻返回 400「交易中的商品不能手动解冻」。 */
    @Test
    void 交易中手动解冻返回400() throws Exception {
        Product product = createOnSaleProduct();
        submit("张三", "13800000001");
        tradeService.enterDeal(product);
        // 商品已被交易自动冻结
        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertEquals(Product.Status.FROZEN, reloaded.getStatus());
        assertEquals("auto", reloaded.getFrozenBy());
        String token = login();
        mockMvc.perform(post("/api/seller/products/" + product.getId() + "/unfreeze")
                        .header("X-Token", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("交易中的商品不能手动解冻"));
    }

    /** 用例 13：标记交易失败后下一位自动递补进入交易（商品保持冻结）。 */
    @Test
    void 标记交易失败后下一位自动递补() {
        Product product = createOnSaleProduct();
        String code1 = submit("张三", "13800000001");
        String code2 = submit("李四", "13800000002");
        tradeService.enterDeal(product);

        Intent head = intentRepository.findByCode(code1).orElseThrow();
        assertEquals(Intent.Status.IN_TRANSACTION, head.getStatus());

        tradeService.markResult(product, "fail");

        Intent failed = intentRepository.findByCode(code1).orElseThrow();
        assertEquals(Intent.Status.FAILED, failed.getStatus());
        assertFalse(failed.isDisposed()); // 待卖家处置
        Intent next = intentRepository.findByCode(code2).orElseThrow();
        assertEquals(Intent.Status.IN_TRANSACTION, next.getStatus()); // 自动递补，无需卖家再次点击
        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertEquals(Product.Status.FROZEN, reloaded.getStatus()); // 商品保持冻结
        assertEquals("auto", reloaded.getFrozenBy());
    }

    /** 用例 14：失败买家重新排队后原口令码仍然有效且排到队尾。 */
    @Test
    void 失败买家重新排队后原口令码有效且排到队尾() throws Exception {
        Product product = createOnSaleProduct();
        String codeA = submit("张三", "13800000001");
        String codeB = submit("李四", "13800000002");
        String codeC = submit("王五", "13800000003");
        tradeService.enterDeal(product);
        tradeService.markResult(product, "fail"); // A 失败，B 自动递补进入交易，C 仍在排队
        // 递补确认：B 已进入交易，C 仍在排队
        assertEquals(Intent.Status.IN_TRANSACTION, intentRepository.findByCode(codeB).orElseThrow().getStatus());
        assertEquals(Intent.Status.QUEUED, intentRepository.findByCode(codeC).orElseThrow().getStatus());

        Intent failedA = intentRepository.findByCode(codeA).orElseThrow();
        tradeService.disposeFailed(failedA, "requeue");

        // 原记录已处置并保留作历史流水
        Intent original = intentRepository.findById(failedA.getId()).orElseThrow();
        assertTrue(original.isDisposed());
        // 新记录沿用原口令码、指向原意向、重新排队
        Intent requeued = intentRepository.findByCode(codeA).orElseThrow();
        assertEquals(Intent.Status.QUEUED, requeued.getStatus());
        assertEquals(failedA.getId(), requeued.getRequeuedFrom());
        assertFalse(requeued.isDisposed());
        // 原口令码查询仍然有效，且排在当前队尾（C 之后，位次 2）
        mockMvc.perform(get("/api/intents/lookup/" + codeA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent.status").value("queued"))
                .andExpect(jsonPath("$.intent.inTransaction").value(false))
                .andExpect(jsonPath("$.intent.position").value(2));
    }

    /** 用例 15：标记交易成功后队列剩余意向全部转为失败，商品下架进历史。 */
    @Test
    void 标记交易成功后队列剩余意向全部转失败() {
        Product product = createOnSaleProduct();
        String code1 = submit("张三", "13800000001");
        String code2 = submit("李四", "13800000002");
        String code3 = submit("王五", "13800000003");
        tradeService.enterDeal(product);
        tradeService.markResult(product, "success");

        Intent winner = intentRepository.findByCode(code1).orElseThrow();
        assertEquals(Intent.Status.SUCCESS, winner.getStatus());
        assertTrue(winner.isDisposed());
        for (String code : List.of(code2, code3)) {
            Intent loser = intentRepository.findByCode(code).orElseThrow();
            assertEquals(Intent.Status.FAILED, loser.getStatus());
            assertTrue(loser.isDisposed());
        }
        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertEquals(Product.Status.OFF_SHELF, reloaded.getStatus());
        assertNotNull(reloaded.getClosedAt());
    }

    /** 用例 16：历史详情的意向流水中不包含 code 字段（口令码不进历史）。 */
    @Test
    void 历史详情意向流水不含口令码() throws Exception {
        Product product = createOnSaleProduct();
        submit("张三", "13800000001");
        submit("李四", "13800000002");
        tradeService.enterDeal(product);
        tradeService.markResult(product, "success"); // 商品下架进历史

        String token = login();
        mockMvc.perform(get("/api/seller/history/" + product.getId())
                        .header("X-Token", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intents.length()").value(2))
                .andExpect(jsonPath("$.intents[0].name").exists())
                .andExpect(jsonPath("$.intents[0].phone").exists())
                .andExpect(jsonPath("$.intents[0].code").doesNotExist())
                .andExpect(jsonPath("$.intents[1].code").doesNotExist());
    }

    /** 直接落库一个在售商品（测试数据准备） */
    private Product createOnSaleProduct() {
        Product product = new Product();
        product.setName("测试商品");
        product.setDescription("测试描述");
        product.setPrice(new BigDecimal("99.90"));
        product.setStatus(Product.Status.ON_SALE);
        return productRepository.save(product);
    }

    /** 通过买家服务提交意向，返回口令码 */
    private String submit(String name, String phone) {
        return intentService.submit(name, phone);
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
}
