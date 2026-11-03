package com.group1.onlineshop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.group1.onlineshop.entity.Buyer;
import com.group1.onlineshop.entity.Intent;
import com.group1.onlineshop.entity.Product;
import com.group1.onlineshop.exception.BizException;
import com.group1.onlineshop.repository.BuyerRepository;
import com.group1.onlineshop.repository.IntentRepository;
import com.group1.onlineshop.repository.ProductRepository;
import com.group1.onlineshop.service.IntentService;
import com.group1.onlineshop.service.ProductService;
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
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
 *   <li>队首<b>自动</b>进入交易（A.2.3-9 + 裁决 C11）：首笔由提交成功后自动触发，
 *       故旧用例中显式的 {@code enterDeal} 调用已全部移除；自动冻结期间仍可继续排队</li>
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
    private ProductService productService;

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
        submit(buyer("zhangsan", "13800000001"), product); // 裁决 C11：首笔提交成功后自动进入交易
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
        long id1 = submit(b1, product); // 首笔提交后自动进入交易（C11）
        long id2 = submit(b2, product); // 自动冻结期间仍可排队

        assertEquals(Intent.Status.IN_TRANSACTION, intent(id1).getStatus());
        assertEquals(Intent.Status.QUEUED, intent(id2).getStatus());

        tradeService.markResult(product, "fail");

        assertEquals(Intent.Status.FAILED, intent(id1).getStatus());
        assertFalse(intent(id1).isDisposed()); // 待卖家处置
        assertNotNull(intent(id1).getDealtAt()); // 失败也要写交易结束时间（买家历史要显示「何时失败」）
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

    /**
     * 用例 15（改写，A.1.3-4）：成交成功且仍有库存、队列中还有下一位 → 扣 1 件不下架，
     * 下一位自动进入下一笔交易（商品因新交易处于 FROZEN，不是 ON_SALE）。
     */
    @Test
    void 成交成功后队列不清空且下一位自动进交易() {
        Product product = createOnSaleProduct("测试商品", 2);
        Buyer b1 = buyer("zhangsan", "13800000001");
        Buyer b2 = buyer("lisi", "13800000002");
        long id1 = submit(b1, product);
        long id2 = submit(b2, product);
        tradeService.markResult(product, "success");

        assertEquals(Intent.Status.SUCCESS, intent(id1).getStatus());
        assertTrue(intent(id1).isDisposed());
        assertNotNull(intent(id1).getDealtAt()); // 交易时间写在意向自身，成交不下架时商品 closedAt 为 null
        assertNull(productRepository.findById(product.getId()).orElseThrow().getClosedAt()); // 成交≠下架
        // 关键：队列不清空，下一位自动进入下一笔交易（而不是被清成失败）
        assertEquals(Intent.Status.IN_TRANSACTION, intent(id2).getStatus());
        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertEquals(Product.Status.FROZEN, reloaded.getStatus()); // 已进入下一笔交易
        assertEquals(1, reloaded.getStock());                        // 扣 1 件，剩 1
        assertNull(reloaded.getClosedAt());                          // 未下架
    }

    /**
     * 用例 15a（补，A.1.3-4）：队列为空且成交后仍有库存 → 商品回 ON_SALE 继续在售。
     *
     * <p>原用例 15 的第二位买家会自动进入下一笔交易，把商品状态覆盖成 FROZEN，
     * 导致「队列空 + 库存有余 → 回在售」这条核心路径长期零覆盖。
     */
    @Test
    void 队列为空且库存有余时成交后商品回在售() {
        Product product = createOnSaleProduct("测试商品", 2);
        Buyer b1 = buyer("zhangsan", "13800000001");
        long id1 = submit(b1, product);
        tradeService.markResult(product, "success");

        assertEquals(Intent.Status.SUCCESS, intent(id1).getStatus());
        Product reloaded = productRepository.findById(product.getId()).orElseThrow();
        assertEquals(Product.Status.ON_SALE, reloaded.getStatus()); // 队列已空、库存有余 → 继续卖
        assertEquals(1, reloaded.getStock());                        // 扣 1 件，剩 1
        assertNull(reloaded.getFrozenBy());                          // 交易冻结痕迹清空
        assertNull(reloaded.getFrozenAt());
        assertNull(reloaded.getClosedAt());                          // 成交不等于下架
    }

    /** 用例 15b（W-5）：库存扣减归零 → 商品售罄下架，队列未成交意向置「售罄失效」。 */
    @Test
    void 库存归零时商品售罄且队列未成交意向置售罄失效() {
        Product product = createOnSaleProduct("测试商品", 1);
        Buyer b1 = buyer("zhangsan", "13800000001");
        Buyer b2 = buyer("lisi", "13800000002");
        long id1 = submit(b1, product);
        long id2 = submit(b2, product);
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
                // W-4 / 裁决 C11：首笔提交成功后即自动进入交易
                .andExpect(jsonPath("$.intent.status").value("in_transaction"));
    }

    /**
     * 用例 19（改写，W-3 补充）：买家历史必须走真实接口 {@code GET /api/buyers/history}。
     *
     * <p>原用例调 {@code IntentService.listMyIntents}，该方法全仓无生产调用者（死代码），
     * 真实接口由 {@code BuyerService#history} 承接；此前的「通过」不能证明线上正确。
     */
    @Test
    void 我的历史通过真实接口返回三值展示() throws Exception {
        Buyer b = buyer("zhangsan", "13800000001");
        b.setToken("test-buyer-token-" + System.nanoTime());
        buyerRepository.save(b);
        Product product = createOnSaleProduct("二手自行车", 2);
        submit(b, product); // C11：首笔提交即进入交易，买家侧仍展示为「待交易」

        Map<String, Object> waiting = history(b);
        assertEquals(1, waiting.get("total"));
        Map<String, Object> queued = item(waiting, 0);
        assertEquals("待交易", queued.get("result"));
        assertEquals("二手自行车", queued.get("productName"));
        assertNull(queued.get("dealTime")); // 未成交：无交易时间

        tradeService.markResult(product, "success");

        Map<String, Object> done = history(b);
        Map<String, Object> success = item(done, 0);
        assertEquals("成功", success.get("result"));
        assertNotNull(success.get("dealTime")); // 来自 dealtAt
        assertNull(productRepository.findById(product.getId()).orElseThrow().getClosedAt()); // 成交不下架
    }

    /**
     * 用例 19b（补）：FAILED / SOLD_OUT / CANCELLED 三种终态在买家侧统一展示为「失败」。
     * 原用例只覆盖「待交易」与「成功」，三种失败映射此前零覆盖。
     */
    @Test
    void 我的历史把失败售罄取消统一展示为失败() throws Exception {
        Buyer b = buyer("zhangsan", "13800000001");
        b.setToken("test-buyer-token-" + System.nanoTime());
        buyerRepository.save(b);
        Product p1 = createOnSaleProduct("失败商品", 1);
        Product p2 = createOnSaleProduct("售罄商品", 1);
        Product p3 = createOnSaleProduct("取消商品", 1);

        Intent failed = newIntent(b.getId(), p1.getId(), Intent.Status.FAILED, LocalDateTime.now().minusMinutes(3));
        failed.setDealtAt(LocalDateTime.now().minusMinutes(3));
        intentRepository.save(failed);
        newIntent(b.getId(), p2.getId(), Intent.Status.SOLD_OUT, LocalDateTime.now().minusMinutes(2));
        newIntent(b.getId(), p3.getId(), Intent.Status.CANCELLED, LocalDateTime.now().minusMinutes(1));

        Map<String, Object> body = history(b);
        assertEquals(3, body.get("total"));
        assertEquals("失败", item(body, 0).get("result")); // CANCELLED 最新提交
        assertEquals("失败", item(body, 1).get("result")); // SOLD_OUT
        assertEquals("失败", item(body, 2).get("result")); // FAILED
    }

    /** 调真实买家历史接口并解析为 Map；不直接调 service，避免再次测到无调用者的方法 */
    @SuppressWarnings("unchecked")
    private Map<String, Object> history(Buyer b) throws Exception {
        MvcResult r = mockMvc.perform(get("/api/buyers/history").header("X-Token", b.getToken()))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readValue(r.getResponse().getContentAsString(StandardCharsets.UTF_8), Map.class);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> item(Map<String, Object> body, int index) {
        return ((List<Map<String, Object>>) body.get("items")).get(index);
    }

    /** 直接落库一条意向（提交时间显式指定，保证排序确定） */
    private Intent newIntent(Long buyerId, Long productId, Intent.Status status, LocalDateTime submittedAt) {
        Intent it = new Intent();
        it.setBuyerId(buyerId);
        it.setProductId(productId);
        it.setName("测试买家");
        it.setPhone("13800000000");
        it.setStatus(status);
        it.setSubmittedAt(submittedAt);
        return intentRepository.save(it);
    }

    /**
     * 用例 20（裁决 C11 / W-4）：自动冻结（交易进行中）期间仍可提交、队列继续累积，
     * 且同一商品同一时刻只有一条意向在交易；手动冻结（卖家停售）期间提交被拒，两类文案区分。
     *
     * <p>同时回归 W-7 压测暴露的并发缺陷：{@code tryStartDeal} 在已有交易时必须幂等返回 false，
     * 不得把第二条意向推进交易（基线版曾出现 3 条 IN_TRANSACTION）。
     */
    @Test
    void 自动冻结期间仍可排队而手动冻结拒绝提交() {
        Product product = createOnSaleProduct("自动冻结商品", 5);
        Buyer b1 = buyer("zhangsan", "13800000001");
        Buyer b2 = buyer("lisi", "13800000002");

        long id1 = submit(b1, product); // 首笔：提交成功即自动进入交易
        assertEquals(Intent.Status.IN_TRANSACTION, intent(id1).getStatus());
        Product frozen = productRepository.findById(product.getId()).orElseThrow();
        assertEquals(Product.Status.FROZEN, frozen.getStatus());
        assertEquals("auto", frozen.getFrozenBy());

        long id2 = submit(b2, product); // 自动冻结期间仍可提交，留在队列
        assertEquals(Intent.Status.QUEUED, intent(id2).getStatus());
        assertEquals(1, inTransactionCount(product.getId())); // 没有推进第二条
        assertFalse(tradeService.tryStartDeal(product));       // 幂等：已有交易时不重复推进

        // 手动冻结：卖家停售，提交被拒（文案与「交易中」区分）
        Product manual = createOnSaleProduct("手动冻结商品", 3);
        productService.freeze(manual.getId());
        BizException ex = assertThrows(BizException.class, () -> submit(b1, manual));
        assertEquals("卖家已暂停接单，暂不接收新意向", ex.getMessage());
    }

    /**
     * 用例 21（W-4 端到端，MockMvc）：首笔提交即自动进交易；自动冻结期间第二笔仍可提交；
     * 卖家「手动进入交易」端点已下线；商品详情暴露 {@code frozenBy} 供买家端区分文案。
     */
    @Test
    void 提交即自动进交易且卖家手动端点已下线() throws Exception {
        Product product = createOnSaleProduct("端到端商品", 3);
        Buyer b1 = buyer("zhangsan", "13800000001");
        Buyer b2 = buyer("lisi", "13800000002");
        b1.setToken("e2e-token-1-" + System.nanoTime());
        buyerRepository.save(b1);
        b2.setToken("e2e-token-2-" + System.nanoTime());
        buyerRepository.save(b2);

        // 首笔：提交成功即自动进入交易
        mockMvc.perform(post("/api/intents")
                        .header("X-Token", b1.getToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + product.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent.status").value("in_transaction"));

        // 自动冻结期间第二笔仍可提交，留在队列（C11）
        mockMvc.perform(post("/api/intents")
                        .header("X-Token", b2.getToken())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"productId\":" + product.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.intent.status").value("queued"));

        // 卖家「手动进入交易」端点已下线（C11）
        mockMvc.perform(post("/api/seller/products/" + product.getId() + "/deal")
                        .header("X-Token", login()))
                .andExpect(status().isNotFound());

        // 商品详情带 frozenBy，买家端据此区分「交易中」与「卖家停售」
        mockMvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.status").value("frozen"))
                .andExpect(jsonPath("$.product.frozenBy").value("auto"));
    }

    /**
     * 用例 22（W-4 并发）：10 个买家同时提交同一商品，只能推进<b>一笔</b>交易，其余全部正常排队、无一失败。
     *
     * <p>复现路径：JVM 锁 + 商品行 {@code FOR UPDATE} 当前读；若只有 JVM 锁而无行锁，
     * 后到线程可能在前者事务提交前读到旧的 {@code ON_SALE}，推进出多条 {@code IN_TRANSACTION}。
     */
    @Test
    void 并发提交只推进一笔交易且其余正常排队() throws Exception {
        Product product = createOnSaleProduct("并发提交商品", 10);
        int n = 10;
        List<Buyer> buyers = new ArrayList<>();
        for (int i = 0; i < n; i++) {
            buyers.add(buyer("concurrent" + i, "1380000" + (1000 + i)));
        }

        ExecutorService pool = Executors.newFixedThreadPool(n);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(n);
        AtomicInteger failed = new AtomicInteger();

        for (Buyer b : buyers) {
            pool.submit(() -> {
                try {
                    start.await();
                    intentService.submit(b, product.getId());
                } catch (Exception e) {
                    failed.incrementAndGet();
                } finally {
                    done.countDown();
                }
            });
        }
        start.countDown();
        assertTrue(done.await(30, TimeUnit.SECONDS), "并发提交超时");
        pool.shutdown();

        assertEquals(0, failed.get(), "自动冻结期间提交不应失败");
        assertEquals(n, inTransactionCount(product.getId())
                        + intentRepository.findByProductIdAndStatusOrderBySubmittedAtAscIdAsc(
                                product.getId(), Intent.Status.QUEUED).size(),
                "10 笔都应落库");
        assertEquals(1, inTransactionCount(product.getId()), "同一时刻只能有一笔交易");
        assertEquals("auto", productRepository.findById(product.getId()).orElseThrow().getFrozenBy());
    }

    private long inTransactionCount(Long productId) {
        return intentRepository
                .findByProductIdAndStatusOrderBySubmittedAtAscIdAsc(productId, Intent.Status.IN_TRANSACTION)
                .size();
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
