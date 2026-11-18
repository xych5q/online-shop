package com.group1.onlineshop;

import com.group1.onlineshop.entity.Product;
import com.group1.onlineshop.entity.StockLog;
import com.group1.onlineshop.exception.BizException;
import com.group1.onlineshop.repository.ProductRepository;
import com.group1.onlineshop.repository.StockLogRepository;
import com.group1.onlineshop.service.ProductService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L3-2 库存扣减与回补（商品侧）测试。
 *
 * <p>测试对象：{@link ProductService} 对订单域暴露的三个原子方法
 * （deductStock / restoreStock / refreshSaleStatusAfterRestore）。
 *
 * <p>事务口径（G16 组内裁定）：三个方法均为 {@code Propagation.MANDATORY}，自身不开事务；
 * 测试用 {@link TransactionTemplate} 模拟订单域的事务边界——
 * 正常路径包在事务里调用，异常路径验证整事务回滚，另有一条用例验证「无事务调用被拒」。
 *
 * <p>覆盖：
 * <ul>
 *   <li>按件扣减：库存正确、写「成交扣减」流水、ΣchangeAmount = 当前库存</li>
 *   <li>扣至 0：自动下架（OFF_SHELF + SOLD_OUT + closedAt）</li>
 *   <li>库存不足：抛异常整单回滚，库存与流水都不变</li>
 *   <li>按件回补：只加库存写「取消回补」流水，<b>不直接改状态</b></li>
 *   <li>批量回补后统一刷新：全部回补完才恢复在售（G7）</li>
 *   <li>手动下架：回补后刷新也不恢复在售（G7）</li>
 *   <li>MANDATORY：无事务直接调用被 Spring 拒绝</li>
 * </ul>
 */
@SpringBootTest
class StockCouplingTest {

    @Autowired
    private ProductService productService;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private StockLogRepository stockLogRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** 模拟订单域事务边界的工具 */
    private TransactionTemplate tx;

    /** 夹具：在售商品（stock=5，含一条初始入库流水，保证 ΣchangeAmount = 库存） */
    private Long productId;

    @BeforeEach
    void setUp() {
        stockLogRepository.deleteAll();
        productRepository.deleteAll();

        Product product = new Product();
        product.setName("库存耦合测试商品");
        product.setDescription("test");
        product.setPrice(new BigDecimal("99.90"));
        product.setStock(5);
        product.setStatus(Product.Status.ON_SALE);
        productRepository.save(product);
        productId = product.getId();

        StockLog initial = new StockLog();
        initial.setProductId(productId);
        initial.setOperator("SYSTEM");
        initial.setBeforeStock(0);
        initial.setAfterStock(5);
        initial.setChangeAmount(5);
        initial.setReason("夹具入库");
        stockLogRepository.save(initial);

        tx = new TransactionTemplate(transactionManager);
    }

    // ---------- 扣减 ----------

    /** 用例 1：按件扣减——库存 5 扣 2 剩 3，状态不变，写一条「成交扣减」流水，Σ流水=库存。 */
    @Test
    void 按件扣减并写成交流水() {
        tx.executeWithoutResult(s -> productService.deductStock(productId, 2));

        Product p = reload();
        assertEquals(3, p.getStock());
        assertEquals(Product.Status.ON_SALE, p.getStatus());

        StockLog last = latestLog();
        assertEquals(-2, last.getChangeAmount());
        assertEquals(3, last.getAfterStock());
        assertEquals("成交扣减", last.getReason());
        assertEquals("SYSTEM", last.getOperator());
        assertEquals(p.getStock(), sumLogs(), "ΣchangeAmount = 当前库存");
    }

    /** 用例 2：扣至 0——自动下架（OFF_SHELF + SOLD_OUT + closedAt），流水累加归 0。 */
    @Test
    void 扣至零自动下架() {
        tx.executeWithoutResult(s -> productService.deductStock(productId, 5));

        Product p = reload();
        assertEquals(0, p.getStock());
        assertEquals(Product.Status.OFF_SHELF, p.getStatus());
        assertEquals("SOLD_OUT", p.getOffShelfReason());
        assertNotNull(p.getClosedAt(), "售罄自动下架必须记录下架时间");
        assertEquals(0, sumLogs());
    }

    /** 用例 3：库存不足——抛 400「库存不足」，事务回滚：库存不变、不写流水。 */
    @Test
    void 库存不足整单回滚() {
        long logsBefore = stockLogRepository.count();
        BizException ex = assertThrows(BizException.class,
                () -> tx.executeWithoutResult(s -> productService.deductStock(productId, 6)));
        assertEquals("库存不足", ex.getMessage());

        Product p = reload();
        assertEquals(5, p.getStock());
        assertEquals(Product.Status.ON_SALE, p.getStatus());
        assertEquals(logsBefore, stockLogRepository.count(), "失败不得留下流水");
    }

    /** 用例 4：数量非正——被拒，不影响库存。 */
    @Test
    void 数量非正被拒() {
        BizException ex = assertThrows(BizException.class,
                () -> tx.executeWithoutResult(s -> productService.deductStock(productId, 0)));
        assertEquals("数量必须大于 0", ex.getMessage());
        assertEquals(5, reload().getStock());
    }

    // ---------- 回补 ----------

    /** 用例 5：回补只加库存写流水，不直接恢复在售（状态由统一刷新负责）。 */
    @Test
    void 回补不直接恢复在售() {
        tx.executeWithoutResult(s -> productService.deductStock(productId, 5)); // 售罄下架
        tx.executeWithoutResult(s -> productService.restoreStock(productId, 3));

        Product p = reload();
        assertEquals(3, p.getStock());
        assertEquals(Product.Status.OFF_SHELF, p.getStatus(), "回补本身不得改状态");
        assertEquals("SOLD_OUT", p.getOffShelfReason());

        StockLog last = latestLog();
        assertEquals(3, last.getChangeAmount());
        assertEquals("取消回补", last.getReason());
        assertEquals(p.getStock(), sumLogs());
    }

    /** 用例 6：批量回补完成后统一刷新一次——全部回补前保持下架，刷新后恢复在售并清空痕迹。 */
    @Test
    void 批量回补后统一刷新恢复在售() {
        tx.executeWithoutResult(s -> productService.deductStock(productId, 5));

        // 模拟批量取消：两笔回补在同一事务内完成，但都不判断状态
        tx.executeWithoutResult(s -> {
            productService.restoreStock(productId, 1);
            productService.restoreStock(productId, 2);
            assertEquals(Product.Status.OFF_SHELF, productRepository.findById(productId).orElseThrow().getStatus(),
                    "回补过程中不得逐笔恢复");
            productService.refreshSaleStatusAfterRestore(productId);
        });

        Product p = reload();
        assertEquals(Product.Status.ON_SALE, p.getStatus());
        assertEquals(3, p.getStock());
        assertNull(p.getOffShelfReason());
        assertNull(p.getClosedAt());
        assertEquals(p.getStock(), sumLogs());
    }

    /** 用例 7：手动下架商品——回补后刷新也不恢复在售（G7：只恢复售罄自动下架的）。 */
    @Test
    void 手动下架回补不恢复() {
        // 买家下单前卖家手动下架（此时订单仍可取消回补，FR-C4 下架不牵连进行中订单）
        productService.offshelf(productId);
        assertEquals("MANUAL", reload().getOffShelfReason());

        tx.executeWithoutResult(s -> {
            productService.restoreStock(productId, 2);
            productService.refreshSaleStatusAfterRestore(productId);
        });

        Product p = reload();
        assertEquals(Product.Status.OFF_SHELF, p.getStatus(), "手动下架不得被回补恢复");
        assertEquals("MANUAL", p.getOffShelfReason());
        assertEquals(7, p.getStock());
    }

    // ---------- 事务边界 ----------

    /** 用例 8：MANDATORY——不在事务中调用直接被 Spring 拒绝，防止订单域漏开事务导致部分提交。 */
    @Test
    void 无事务调用被拒() {
        assertThrows(IllegalTransactionStateException.class,
                () -> productService.deductStock(productId, 1));
        assertEquals(5, reload().getStock());
    }

    // ---------- 辅助 ----------

    private Product reload() {
        return productRepository.findById(productId).orElseThrow();
    }

    private StockLog latestLog() {
        List<StockLog> logs = stockLogRepository.findByProductIdOrderByIdDesc(productId);
        assertTrue(!logs.isEmpty(), "必须存在流水");
        return logs.get(0);
    }

    private int sumLogs() {
        return stockLogRepository.findByProductIdOrderByIdDesc(productId).stream()
                .mapToInt(StockLog::getChangeAmount)
                .sum();
    }
}
