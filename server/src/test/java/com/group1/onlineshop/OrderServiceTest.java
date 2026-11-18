package com.group1.onlineshop;

import com.group1.onlineshop.entity.Buyer;
import com.group1.onlineshop.entity.CartItem;
import com.group1.onlineshop.entity.Order;
import com.group1.onlineshop.entity.OrderAuditLog;
import com.group1.onlineshop.entity.Product;
import com.group1.onlineshop.entity.Seller;
import com.group1.onlineshop.exception.BizException;
import com.group1.onlineshop.repository.BuyerRepository;
import com.group1.onlineshop.repository.CartItemRepository;
import com.group1.onlineshop.repository.FavoriteRepository;
import com.group1.onlineshop.repository.IntentRepository;
import com.group1.onlineshop.repository.OrderAuditLogRepository;
import com.group1.onlineshop.repository.OrderItemRepository;
import com.group1.onlineshop.repository.OrderRepository;
import com.group1.onlineshop.repository.ProductRepository;
import com.group1.onlineshop.repository.SellerRepository;
import com.group1.onlineshop.repository.StockLogRepository;
import com.group1.onlineshop.service.OrderService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 订单服务迭代 3 测试（W3-2/W3-3/W3-4，对照《订单域断言清单（迭代3）》A-xx 编号）。
 *
 * <p>覆盖：多件一笔订单与按件扣减、缺货整单拦截（不 catch 吞、事务回滚无残留）、
 * 下架商品拒绝结算、下单移出已勾选、买家/商家取消窗口、批量取消两类核心用例
 * （全过生效 / 含一单不可取消整批拦截）、G7 恢复分流、P5 分页、P2 审计两项。
 * 数据源一律 MySQL（J3-0，不留 H2），测试库 onlineshop_test + create-drop。
 */
@SpringBootTest
class OrderServiceTest {

    private static final String TOKEN_A = "buyer-a-token";
    private static final String TOKEN_B = "buyer-b-token";
    private static final String TOKEN_S = "seller-token";

    @Autowired private OrderService orderService;
    @Autowired private OrderRepository orderRepository;
    @Autowired private OrderItemRepository orderItemRepository;
    @Autowired private OrderAuditLogRepository orderAuditLogRepository;
    @Autowired private CartItemRepository cartItemRepository;
    @Autowired private ProductRepository productRepository;
    @Autowired private StockLogRepository stockLogRepository;
    @Autowired private BuyerRepository buyerRepository;
    @Autowired private SellerRepository sellerRepository;
    @Autowired private FavoriteRepository favoriteRepository;
    @Autowired private IntentRepository intentRepository;

    private Long p1; // 在售 stock=10 price=10.00
    private Long p2; // 在售 stock=1  price=5.00（供扣零售罄/恢复用例）
    private Long p3; // 手动下架 stock=3（供 G7 不恢复用例）
    private Long buyerA;
    private Long buyerB;

    @BeforeEach
    void setUp() {
        orderAuditLogRepository.deleteAll();
        orderItemRepository.deleteAll();
        orderRepository.deleteAll();
        cartItemRepository.deleteAll();
        favoriteRepository.deleteAll();
        intentRepository.deleteAll();
        stockLogRepository.deleteAll();
        productRepository.deleteAll();
        buyerRepository.deleteAll();
        sellerRepository.deleteAll();

        Buyer a = new Buyer();
        a.setUsername("buyerA");
        a.setPasswordHash("x");
        a.setPhone("13800000001");
        a.setToken(TOKEN_A);
        buyerA = buyerRepository.save(a).getId();

        Buyer b = new Buyer();
        b.setUsername("buyerB");
        b.setPasswordHash("x");
        b.setPhone("13800000002");
        b.setToken(TOKEN_B);
        buyerB = buyerRepository.save(b).getId();

        Seller seller = new Seller();
        seller.setUsername("admin");
        seller.setPasswordHash("x");
        seller.setToken(TOKEN_S);
        sellerRepository.save(seller);

        p1 = saveProduct("商品1", "10.00", 10, Product.Status.ON_SALE, null);
        p2 = saveProduct("商品2", "5.00", 1, Product.Status.ON_SALE, null);
        p3 = saveProduct("商品3", "8.00", 3, Product.Status.OFF_SHELF, "MANUAL");
    }

    // ---------- 下单 O0 ----------

    /** A-01/A-02/A-05：多件一笔订单、按件扣减、扣零售罄自动下架、总额=Σ单价×数量。 */
    @Test
    void 多件下单生成一笔订单并按件扣减() {
        Map<String, Object> result = orderService.placeOrder(TOKEN_A, items(
                item(p1, 2), item(p2, 1)));

        assertEquals(true, result.get("ok"));
        Long orderId = ((Number) result.get("orderId")).longValue();
        assertEquals(1, orderRepository.count(), "一笔订单");
        assertEquals(2, orderItemRepository.findByOrderId(orderId).size(), "两条明细");
        assertEquals(0, new BigDecimal("25.00").compareTo((BigDecimal) result.get("totalAmount")));

        Product product1 = productRepository.findById(p1).orElseThrow();
        assertEquals(8, product1.getStock());
        Product product2 = productRepository.findById(p2).orElseThrow();
        assertEquals(0, product2.getStock());
        assertEquals(Product.Status.OFF_SHELF, product2.getStatus());
        assertEquals("SOLD_OUT", product2.getOffShelfReason());

        Order order = orderRepository.findById(orderId).orElseThrow();
        assertEquals(Order.Status.PLACED, order.getStatus());
        assertNotNull(order.getCreatedAt());
        assertEquals(buyerA, order.getBuyerId());
    }

    /** A-03/A-09：缺货整单拦截——订单不生成、库存与购物车均不变；异常未被 catch 吞。 */
    @Test
    void 缺货整单拦截无残留() {
        cart(buyerA, p2, 1); // 购物车 p2×1（库存只有 1，提交要 3）

        // 用 3 件提交（直接传 items，不走购物车勾选数量）
        BizException e = assertThrows(BizException.class,
                () -> orderService.placeOrder(TOKEN_A, items(item(p2, 3))));
        assertEquals("库存不足", e.getMessage());

        assertEquals(0, orderRepository.count(), "订单不生成");
        assertEquals(1, productRepository.findById(p2).orElseThrow().getStock(), "库存不变");
        assertEquals(1, cartItemRepository.findAllByBuyerIdOrderByCreatedAtAsc(buyerA).size(), "购物车不变");
    }

    /** A-04：已下架商品不可结算（服务端复检，G11/B.2.3-3）。 */
    @Test
    void 下架商品整单拒绝() {
        BizException e = assertThrows(BizException.class,
                () -> orderService.placeOrder(TOKEN_A, items(item(p3, 1))));
        assertTrue(e.getMessage().contains("已下架"));
        assertEquals(0, orderRepository.count());
        assertEquals(3, productRepository.findById(p3).orElseThrow().getStock(), "库存不变");
    }

    /** A-06/A-07：下单移出已勾选条目，未勾选保留（G13）。 */
    @Test
    void 下单移出已勾选保留未勾选() {
        cart(buyerA, p1, 1);
        cart(buyerA, p2, 1);

        orderService.placeOrder(TOKEN_A, items(item(p1, 1))); // 只提交 p1

        assertEquals(false, cartItemRepository.existsByBuyerIdAndProductId(buyerA, p1), "已勾选移出");
        assertTrue(cartItemRepository.existsByBuyerIdAndProductId(buyerA, p2), "未勾选保留");
    }

    /** A-08：未登录 401。 */
    @Test
    void 未登录下单被拒() {
        BizException e = assertThrows(BizException.class,
                () -> orderService.placeOrder("bad-token", items(item(p1, 1))));
        assertEquals(401, e.getStatus());
    }

    // ---------- 买家取消 C1 ----------

    /** A-17/A-21：前三态可取消、不填原因、按件回补、SOLD_OUT 恢复在售（G7/口径 #3）。 */
    @Test
    void 买家取消回补并恢复售罄商品() {
        Map<String, Object> placed = orderService.placeOrder(TOKEN_A, items(item(p2, 1)));
        long orderId = ((Number) placed.get("orderId")).longValue();
        assertEquals(Product.Status.OFF_SHELF, productRepository.findById(p2).orElseThrow().getStatus());

        Map<String, Object> cancelled = orderService.buyerCancel(TOKEN_A, orderId);
        assertEquals("CANCELLED", cancelled.get("status"));

        Product product2 = productRepository.findById(p2).orElseThrow();
        assertEquals(1, product2.getStock(), "按原件数回补");
        assertEquals(Product.Status.ON_SALE, product2.getStatus(), "SOLD_OUT 且库存≥1 → 自动恢复在售");
        assertEquals(null, product2.getOffShelfReason());
    }

    /** A-18/A-19：开始发货后买家不可取消；交易完成/已取消拒绝。 */
    @Test
    void 买家取消窗口校验() {
        long shipping = advanceTo(p1, 1, 3); // PLACED→CONFIRMED→PREPARED→SHIPPING
        BizException e1 = assertThrows(BizException.class, () -> orderService.buyerCancel(TOKEN_A, shipping));
        assertTrue(e1.getMessage().contains("不可取消"));

        long completed = advanceTo(p1, 1, 4);
        BizException e2 = assertThrows(BizException.class, () -> orderService.buyerCancel(TOKEN_A, completed));
        assertTrue(e2.getMessage().contains("交易已完成"));

        // 取消后二次取消拒绝
        Map<String, Object> placed = orderService.placeOrder(TOKEN_A, items(item(p1, 1)));
        orderService.buyerCancel(TOKEN_A, ((Number) placed.get("orderId")).longValue());
        BizException e3 = assertThrows(BizException.class,
                () -> orderService.buyerCancel(TOKEN_A, ((Number) placed.get("orderId")).longValue()));
        assertTrue(e3.getMessage().contains("已取消"));
    }

    /** A-20：非本人订单取消被拒。 */
    @Test
    void 非本人订单取消被拒() {
        Map<String, Object> placed = orderService.placeOrder(TOKEN_A, items(item(p1, 1)));
        long orderId = ((Number) placed.get("orderId")).longValue();
        BizException e = assertThrows(BizException.class, () -> orderService.buyerCancel(TOKEN_B, orderId));
        assertEquals(403, e.getStatus());
    }

    /** A-22：手动下架的商品回补后不自动恢复（G7）。 */
    @Test
    void 手动下架回补后不恢复() {
        Map<String, Object> placed = orderService.placeOrder(TOKEN_A, items(item(p1, 1)));
        long orderId = ((Number) placed.get("orderId")).longValue();

        // 卖家在履约前手动下架该商品
        Product product1 = productRepository.findById(p1).orElseThrow();
        product1.setStatus(Product.Status.OFF_SHELF);
        product1.setOffShelfReason("MANUAL");
        productRepository.save(product1);

        orderService.buyerCancel(TOKEN_A, orderId);

        Product reloaded = productRepository.findById(p1).orElseThrow();
        assertEquals(10, reloaded.getStock(), "回补到位（1-1+10=10）");
        assertEquals(Product.Status.OFF_SHELF, reloaded.getStatus(), "手动下架不恢复（G7）");
        assertEquals("MANUAL", reloaded.getOffShelfReason());
    }

    // ---------- 推进 A1~A4 ----------

    /** A-10/A-11/A-13：逐级推进至交易完成；终态拒绝；推进不改库存。 */
    @Test
    void 推进逐级单向至交易完成() {
        Map<String, Object> placed = orderService.placeOrder(TOKEN_A, items(item(p1, 2)));
        long orderId = ((Number) placed.get("orderId")).longValue();
        int stockBefore = productRepository.findById(p1).orElseThrow().getStock();

        assertEquals("CONFIRMED", orderService.advance(TOKEN_S, orderId).get("status"));
        assertEquals("PREPARED", orderService.advance(TOKEN_S, orderId).get("status"));
        assertEquals("SHIPPING", orderService.advance(TOKEN_S, orderId).get("status"));
        assertEquals("COMPLETED", orderService.advance(TOKEN_S, orderId).get("status"));

        assertEquals(stockBefore, productRepository.findById(p1).orElseThrow().getStock(), "推进不改库存");
        BizException e = assertThrows(BizException.class, () -> orderService.advance(TOKEN_S, orderId));
        assertTrue(e.getMessage().contains("交易已完成"));
    }

    /** A-16/A-25：P2 审计——推进与取消各写一条留痕。 */
    @Test
    void 推进与取消写审计日志() {
        Map<String, Object> placed = orderService.placeOrder(TOKEN_A, items(item(p1, 1)));
        long orderId = ((Number) placed.get("orderId")).longValue();
        orderService.advance(TOKEN_S, orderId);
        orderService.buyerCancel(TOKEN_A, orderId);

        List<OrderAuditLog> logs = orderAuditLogRepository.findAllByOrderIdOrderByCreatedAtDesc(orderId);
        assertEquals(2, logs.size());
        assertEquals(OrderAuditLog.Action.CANCEL, logs.get(0).getAction());
        assertEquals("BUYER", logs.get(0).getOperatorType());
        assertEquals("buyerA", logs.get(0).getOperatorName());
        assertEquals(OrderAuditLog.Action.ADVANCE, logs.get(1).getAction());
        assertEquals("SELLER", logs.get(1).getOperatorType());
        assertEquals("admin", logs.get(1).getOperatorName());
        assertEquals("PLACED", logs.get(1).getFromStatus());
        assertEquals("CONFIRMED", logs.get(1).getToStatus());
    }

    // ---------- 商家单笔取消 C2 ----------

    /** A-26/A-27：商家比买家多一个可取消态（开始发货仍可取消）；COMPLETED 拒绝。 */
    @Test
    void 商家可取消开始发货订单() {
        long shipping = advanceTo(p1, 1, 3);
        Map<String, Object> cancelled = orderService.sellerCancel(TOKEN_S, shipping);
        assertEquals("CANCELLED", cancelled.get("status"));
        assertEquals(10, productRepository.findById(p1).orElseThrow().getStock(), "按件回补");

        long completed = advanceTo(p1, 1, 4);
        BizException e = assertThrows(BizException.class, () -> orderService.sellerCancel(TOKEN_S, completed));
        assertTrue(e.getMessage().contains("交易已完成"));
    }

    // ---------- 批量取消 C3（G5） ----------

    /** A-29【用例一：全部可取消】——同一事务整批生效、同商品回补一次到位、统一判断恢复。 */
    @Test
    void 批量取消全部可取消时整批生效() {
        Long p4 = saveProduct("商品4", "3.00", 10, Product.Status.ON_SALE, null);
        long orderA = placed(TOKEN_A, item(p4, 1));
        long orderB = placed(TOKEN_A, item(p4, 1));
        long orderC = placed(TOKEN_A, item(p1, 1));
        assertEquals(8, productRepository.findById(p4).orElseThrow().getStock(), "两单各扣一件");

        Map<String, Object> result = orderService.batchCancel(TOKEN_S, List.of(orderA, orderB, orderC));

        assertEquals(true, result.get("ok"));
        assertEquals(3, ((List<?>) result.get("cancelled")).size());
        assertEquals(3, orderRepository.findAllByStatus(Order.Status.CANCELLED).size());
        assertEquals(10, productRepository.findById(p4).orElseThrow().getStock(), "同商品两笔回补一次到位（8+1+1=10）");
        assertEquals(10, productRepository.findById(p1).orElseThrow().getStock());
    }

    /** A-30【用例二：含一单不可取消】——整批不执行、返回失败清单，订单状态与库存全部不变。 */
    @Test
    void 批量取消含不可取消订单时整批拦截() {
        long okOrder = placed(TOKEN_A, item(p1, 1));
        long completed = advanceTo(p1, 1, 4);
        int stockBefore = productRepository.findById(p1).orElseThrow().getStock();

        Map<String, Object> result = orderService.batchCancel(TOKEN_S, List.of(okOrder, completed));

        assertEquals(false, result.get("ok"));
        assertEquals(List.of(), result.get("executed"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failed = (List<Map<String, Object>>) result.get("failed");
        assertEquals(1, failed.size());
        assertEquals(completed, ((Number) failed.get(0).get("orderId")).longValue());
        assertEquals("COMPLETED", failed.get(0).get("reason"));
        assertEquals(Order.Status.PLACED, orderRepository.findById(okOrder).orElseThrow().getStatus(), "可取消单也保持不变");
        assertEquals(stockBefore, productRepository.findById(p1).orElseThrow().getStock(), "库存不变");
    }

    /** A-31：重复单号与不存在单号视为预校验失败，整批不执行。 */
    @Test
    void 批量取消预校验重复与不存在() {
        long orderId = placed(TOKEN_A, item(p1, 1));
        Map<String, Object> result = orderService.batchCancel(TOKEN_S, List.of(orderId, orderId, 999999L));
        assertEquals(false, result.get("ok"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> failed = (List<Map<String, Object>>) result.get("failed");
        assertEquals(2, failed.size());
        assertEquals(Order.Status.PLACED, orderRepository.findById(orderId).orElseThrow().getStatus());
    }

    // ---------- 列表 P5 ----------

    /** A-34：我的订单每页 10 条、倒序、total 正确。 */
    @Test
    void 我的订单分页每页十条() {
        Long p5 = saveProduct("分页商品", "1.00", 20, Product.Status.ON_SALE, null);
        for (int i = 0; i < 12; i++) {
            placed(TOKEN_A, item(p5, 1));
        }
        Map<String, Object> page1 = orderService.myOrders(TOKEN_A, 1);
        assertEquals(12L, page1.get("total"));
        assertEquals(10, ((List<?>) page1.get("items")).size());
        Map<String, Object> page2 = orderService.myOrders(TOKEN_A, 2);
        assertEquals(2, ((List<?>) page2.get("items")).size());

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) page1.get("items");
        String first = (String) items.get(0).get("createdAt");
        String last = (String) items.get(items.size() - 1).get("createdAt");
        assertTrue(first.compareTo(last) >= 0, "按下单时间倒序");
    }

    /** A-36：商家列表状态筛选。 */
    @Test
    void 商家列表按状态筛选() {
        placed(TOKEN_A, item(p1, 1));
        placed(TOKEN_A, item(p1, 1));
        long cancelledId = placed(TOKEN_A, item(p1, 1));
        orderService.buyerCancel(TOKEN_A, cancelledId);

        Map<String, Object> result = orderService.sellerOrders(TOKEN_S, 1, "CANCELLED");
        assertEquals(1L, result.get("total"));
    }

    // ---------- 夹具 ----------

    private Long saveProduct(String name, String price, int stock, Product.Status status, String reason) {
        Product product = new Product();
        product.setName(name);
        product.setDescription("test");
        product.setPrice(new BigDecimal(price));
        product.setStock(stock);
        product.setStatus(status);
        product.setOffShelfReason(reason);
        return productRepository.save(product).getId();
    }

    private void cart(long buyerId, Long productId, int quantity) {
        CartItem item = new CartItem();
        item.setBuyerId(buyerId);
        item.setProductId(productId);
        item.setQuantity(quantity);
        cartItemRepository.save(item);
    }

    private Map<String, Object> item(Long productId, int quantity) {
        Map<String, Object> item = new HashMap<>();
        item.put("productId", productId);
        item.put("quantity", quantity);
        return item;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> items(Map<String, Object>... entries) {
        return List.of(entries);
    }

    /** 造一笔已下单订单，返回订单号 */
    private long placed(String token, Map<String, Object> item) {
        Map<String, Object> result = orderService.placeOrder(token, items(item));
        return ((Number) result.get("orderId")).longValue();
    }

    /** 造一笔订单并推进 n 步（n=3 → SHIPPING，n=4 → COMPLETED），返回订单号 */
    private long advanceTo(Long productId, int quantity, int steps) {
        long orderId = placed(TOKEN_A, item(productId, quantity));
        for (int i = 0; i < steps; i++) {
            orderService.advance(TOKEN_S, orderId);
        }
        return orderId;
    }
}
