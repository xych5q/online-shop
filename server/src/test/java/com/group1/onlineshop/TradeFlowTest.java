package com.group1.onlineshop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.group1.onlineshop.entity.Buyer;
import com.group1.onlineshop.entity.Intent;
import com.group1.onlineshop.entity.Product;
import com.group1.onlineshop.repository.BuyerRepository;
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
import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 交易流转核心测试（队列与交易域，owner 王振涛；对应任务 W-3 / W-4 / W-5 / W-6）。
 *
 * <p>迭代 2（挑战需求 A）改写说明：
 * <ul>
 *   <li>意向改绑账号：不再有口令码，{@code submit} 走 {@code Buyer} + {@code productId}，
 *       以 {@code buyerId} / 意向 id 检索，不再用 {@code findByCode}</li>
 *   <li>队首<b>自动</b>进入交易（A.2.3-9）：提交后无需再手动调 {@code enterDeal}，
 *       故旧用例中显式的 {@code enterDeal} 调用已移除</li>
 *   <li>成交不等于下架（A.1.3-4）：旧用例 15「成交即下架、队列剩余全部失败」断言已按新口径改写</li>
 *   <li>已删除旧用例 17 / 18（交易中买家撤销与撤销后恢复在售）——A.2.2-9 买家无取消入口，能力已废止</li>
 * </ul>
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
    private BuyerRepository buyerRepository;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    /** 每个用例前清空业务数据（H2 文件库跨测试持久化，必须清理；seller 账号由 DataInitializer 维护，不动） */
    @BeforeEach
    void cleanUp() {
        intentRepository.deleteAll();
        productRepository.deleteAll();
        buyerRepository.deleteAll();
    }

    /** 用例 12：交易中（自动冻结 frozenBy=auto）手动解冻返回 400「交易中的商品不能手动解冻」。 */
    @Test
    void 交易中手动解冻返回400() throws Exception {
        Product product = createOnSaleProduct("测试商品", 5);
        submit(buyer("zhangsan", "13800000001"), product);
        tradeService.enterDeal(product); // W-4 挂起期间：首次进入交易由卖家在后台触发（裁定后改为提交时自动触发）
        // 队首进入交易，商品被自动冻结
        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertEquals(Product.Status.FROZEN, reloaded.getStatus());
        assertEquals("auto", reloaded.getFrozenBy());

        String token = login();
        mockMvc.perform(post("/api/seller/products/" + product.getId() + "/unfreeze")
                        .header("X-Token", token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("交易中的商品不能手动解冻"));
    }

    /** 用例 13：标记交易失败后下一位自动递补进入交易（商品保持冻结，T5）。 */
    @Test
    void 标记交易失败后下一位自动递补() {
        Product product = createOnSaleProduct("测试商品", 5);
        Buyer b1 = buyer("zhangsan", "13800000001");
        Buyer b2 = buyer("lisi", "13800000002");
        long id1 = submit(b1, product);
        long id2 = submit(b2, product);
        tradeService.enterDeal(product); // W-4 挂起期间：卖家后台触发

        assertEquals(Intent.Status.IN_TRANSACTION, intent(id1).getStatus());
        assertEquals(Intent.Status.QUEUED, intent(id2).getStatus());

        tradeService.markResult(product, "fail");

        assertEquals(Intent.Status.FAILED, intent(id1).getStatus());
        assertFalse(intent(id1).isDisposed()); // 待卖家处置
        assertEquals(Intent.Status.IN_TRANSACTION, intent(id2).getStatus()); // 自动递补，无需卖家点击
        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertEquals(Product.Status.FROZEN, reloaded.getStatus());
        assertEquals("auto", reloaded.getFrozenBy());
    }

    /** 用例 14（改写）：requeue 沿用账号与电话、不再生成口令码，并排到队尾。 */
    @Test
    void 失败买家重新排队后沿用账号且排到队尾() {
        Product product = createOnSaleProduct("测试商品", 5);
        Buyer b1 = buyer("zhangsan", "13800000001");
        Buyer b2 = buyer("lisi", "13800000002");
        Buyer b3 = buyer("wangwu", "13800000003");
        long id1 = submit(b1, product);
        long id2 = submit(b2, product);
        long id3 = submit(b3, product);
        tradeService.enterDeal(product);          // W-4 挂起期间：卖家后台触发
        tradeService.markResult(product, "fail"); // b1 失败，b2 自动递补，b3 仍在排队
        assertEquals(Intent.Status.IN_TRANSACTION, intent(id2).getStatus());
        assertEquals(Intent.Status.QUEUED, intent(id3).getStatus());

        Intent failed = intent(id1);
        tradeService.disposeFailed(failed, "requeue");

        // 原记录保留作历史流水并置已处置
        assertTrue(intent(id1).isDisposed());
        // 新记录：沿用原账号与电话快照、code=null、排到队尾（在 b3 之后，位次 2）
        List<Intent> queue = intentRepository
                .findByProductIdAndStatusOrderBySubmittedAtAscIdAsc(product.getId(), Intent.Status.QUEUED);
        assertEquals(2, queue.size());
        Intent requeued = queue.get(1);
        assertEquals(Intent.Status.QUEUED, requeued.getStatus());
        assertEquals(id1, requeued.getRequeuedFrom());
        assertFalse(requeued.isDisposed());
        assertEquals(b1.getId(), requeued.getBuyerId());   // 沿用原账号（A.2.3-8）
        assertEquals("13800000001", requeued.getPhone());  // 沿用电话快照
        assertNull(requeued.getCode());                     // 口令码整体废弃（C6）
    }

    /** 用例 15（改写，A.1.3-4）：成交成功且仍有库存 → 商品<b>回在售继续卖</b>，队列不清空，队首自动进下一笔。 */
    @Test
    void 成交成功且仍有库存时商品回在售且队列不清空() {
        Product product = createOnSaleProduct("测试商品", 2);
        Buyer b1 = buyer("zhangsan", "13800000001");
        Buyer b2 = buyer("lisi", "13800000002");
        long id1 = submit(b1, product);
        long id2 = submit(b2, product);
        tradeService.enterDeal(product); // W-4 挂起期间：卖家后台触发

        tradeService.markResult(product, "success");

        assertEquals(Intent.Status.SUCCESS, intent(id1).getStatus());
        assertTrue(intent(id1).isDisposed());
        // 关键：队列不清空，下一位自动进入下一笔交易（而不是被清成失败）
        assertEquals(Intent.Status.IN_TRANSACTION, intent(id2).getStatus());
        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertEquals(Product.Status.FROZEN, reloaded.getStatus()); // 已进入下一笔交易
        assertEquals(1, reloaded.getStock());                        // 扣 1 件，剩 1
        assertNull(reloaded.getClosedAt());                          // 未下架
    }

    /** 用例 15b（W-5）：库存扣减归零 → 商品售罄下架，队列未成交意向置「售罄失效」。 */
    @Test
    void 库存归零时商品售罄且队列未成交意向置售罄失效() {
        Product product = createOnSaleProduct("测试商品", 1);
        Buyer b1 = buyer("zhangsan", "13800000001");
        Buyer b2 = buyer("lisi", "13800000002");
        long id1 = submit(b1, product);
        long id2 = submit(b2, product);
        tradeService.enterDeal(product); // W-4 挂起期间：卖家后台触发

        tradeService.markResult(product, "success");

        assertEquals(Intent.Status.SUCCESS, intent(id1).getStatus());
        assertEquals(Intent.Status.SOLD_OUT, intent(id2).getStatus()); // 售罄失效
        assertTrue(intent(id2).isDisposed());
        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertEquals(Product.Status.SOLD_OUT, reloaded.getStatus());
        assertEquals(0, reloaded.getStock());
        assertEquals("SOLD_OUT", reloaded.getOffShelfReason());
        assertNotNull(reloaded.getClosedAt());
    }

    /** 用例 16：任何环节都不再产生口令码（code 恒为 null，C6）。 */
    @Test
    void 全流程不再产生口令码() {
        Product product = createOnSaleProduct("测试商品", 1);
        long id1 = submit(buyer("zhangsan", "13800000001"), product);
        long id2 = submit(buyer("lisi", "13800000002"), product);
        tradeService.enterDeal(product); // W-4 挂起期间：卖家后台触发
        tradeService.markResult(product, "success");

        for (long id : List.of(id1, id2)) {
            assertNull(intent(id).getCode());
        }
    }

    /** 用例 17（W-6）：提交购买意向不扣库存。 */
    @Test
    void 提交意向不扣库存() {
        Product product = createOnSaleProduct("测试商品", 3);
        submit(buyer("zhangsan", "13800000001"), product);
        submit(buyer("lisi", "13800000002"), product);
        assertEquals(3, productRepository.findById(product.getId()).orElseThrow().getStock());
    }

    /** 用例 18（W-3）：提交意向必须登录态，未登录返回 401；登录后出参不含 code。 */
    @Test
    void 提交意向必须登录态且出参不含口令码() throws Exception {
        Product product = createOnSaleProduct("测试商品", 3);

        mockMvc.perform(post("/api/intents")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + product.getId() + "}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error").value("未登录或登录已失效"));

        Buyer b = buyer("zhangsan", "13800000001");
        b.setToken("test-buyer-token-" + System.nanoTime());
        buyerRepository.save(b);

        mockMvc.perform(post("/api/intents")
                        .header("X-Token", b.getToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + product.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent.intentId").exists())
                .andExpect(jsonPath("$.intent.code").doesNotExist())
                // W-4 挂起期间：提交只入队，不立即进入交易
                .andExpect(jsonPath("$.intent.status").value("queued"));
    }

    // ---------- 测试数据准备 ----------

    private Product createOnSaleProduct(String name, int stock) {
        Product product = new Product();
        product.setName(name);
        product.setDescription("测试描述");
        product.setPrice(new BigDecimal("99.90"));
        product.setStatus(Product.Status.ON_SALE);
        product.setStock(stock); // 迭代 2：发布时库存 ≥1，成交时扣减
        return productRepository.save(product);
    }

    private Buyer buyer(String username, String phone) {
        Buyer b = new Buyer();
        b.setUsername(username);
        b.setPasswordHash("not-used-in-this-test");
        b.setPhone(phone);
        b.setCreatedAt(LocalDateTime.now());
        return buyerRepository.save(b);
    }

    /** 提交意向，返回意向 id（迭代 2：登录态 + 指定商品，无口令码） */
    private long submit(Buyer buyer, Product product) {
        intentService.submit(buyer, product.getId());
        return intentRepository.findByBuyerIdOrderBySubmittedAtDescIdDesc(buyer.getId()).get(0).getId();
    }

    private Intent intent(long id) {
        return intentRepository.findById(id).orElseThrow();
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
