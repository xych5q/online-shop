package com.group1.onlineshop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.group1.onlineshop.entity.Category;
import com.group1.onlineshop.entity.Product;
import com.group1.onlineshop.entity.StockLog;
import com.group1.onlineshop.repository.CategoryRepository;
import com.group1.onlineshop.repository.IntentRepository;
import com.group1.onlineshop.repository.ProductRepository;
import com.group1.onlineshop.repository.StockLogRepository;
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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 卖家后台管理接口 HTTP 层测试（挑战需求 A.1.2 类别管理 / A.1.2-4 改挂类别 / A.1.3-5 调库存 / 上下架）。
 * 用 MockMvc 走真实 Spring MVC 链路，统一异常返回经 GlobalExceptionHandler 后断言。
 * 规则以 docs/design/接口设计-卖家后台管理.md 为准。
 */
@SpringBootTest
@AutoConfigureMockMvc
class SellerAdminApiTest {

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

    @Autowired
    private StockLogRepository stockLogRepository;

    /** 每个用例的固定夹具：一级「测试分类」 + 二级「测试子类」 */
    private Long rootId;
    private Long childId;

    /** 每个用例前清空业务数据（H2 文件库跨测试持久化，必须清理；seller 账号由 DataInitializer 维护，不动） */
    @BeforeEach
    void cleanUp() {
        stockLogRepository.deleteAll();
        intentRepository.deleteAll();
        productRepository.deleteAll();
        categoryRepository.deleteAll();
        Category root = new Category();
        root.setName("测试分类");
        root.setParentId(null);
        rootId = categoryRepository.save(root).getId();
        Category child = new Category();
        child.setName("测试子类");
        child.setParentId(rootId);
        childId = categoryRepository.save(child).getId();
    }

    // ---------- 类别管理 ----------

    /** 用例 1：新增一级类别成功（parentId 不传）。 */
    @Test
    void 新增一级类别成功() throws Exception {
        mockMvc.perform(post("/api/seller/categories")
                        .header("X-Token", login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"图书\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.id").isNumber())
                .andExpect(jsonPath("$.name").value("图书"))
                .andExpect(jsonPath("$.parentId").isEmpty());
    }

    /** 用例 2：在一级类别下新增二级类别成功。 */
    @Test
    void 新增二级类别成功() throws Exception {
        mockMvc.perform(post("/api/seller/categories")
                        .header("X-Token", login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"小说\",\"parentId\":" + rootId + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.parentId").value(rootId));
    }

    /** 用例 3：缺名称新增 → 400「类别名称必填」。 */
    @Test
    void 缺名称新增被拒() throws Exception {
        mockMvc.perform(post("/api/seller/categories")
                        .header("X-Token", login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("类别名称必填"));
    }

    /** 用例 4：父类别不存在 → 404「父类别不存在」。 */
    @Test
    void 父类别不存在被拒() throws Exception {
        mockMvc.perform(post("/api/seller/categories")
                        .header("X-Token", login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"小说\",\"parentId\":999}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error").value("父类别不存在"));
    }

    /** 用例 5：同级重名 → 400「同级类别名称已存在」。 */
    @Test
    void 同级重名被拒() throws Exception {
        mockMvc.perform(post("/api/seller/categories")
                        .header("X-Token", login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"测试分类\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("同级类别名称已存在"));
    }

    /** 用例 6：改名成功且落库。 */
    @Test
    void 改名成功() throws Exception {
        mockMvc.perform(put("/api/seller/categories/" + childId)
                        .header("X-Token", login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"数码配件\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        assertEquals("数码配件", categoryRepository.findById(childId).orElseThrow().getName());
    }

    /** 用例 7：删除挂着商品的二级类别 → 400「该类别下还有商品，不能删除」。 */
    @Test
    void 有商品的类别不可删() throws Exception {
        createOnSaleProduct();
        mockMvc.perform(delete("/api/seller/categories/" + childId)
                        .header("X-Token", login()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("该类别下还有商品，不能删除"));
    }

    /** 用例 8：删除还有子类别的一级类别 → 400「请先删除该类别下的子类别」。 */
    @Test
    void 有子类别的类别不可删() throws Exception {
        mockMvc.perform(delete("/api/seller/categories/" + rootId)
                        .header("X-Token", login()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("请先删除该类别下的子类别"));
    }

    /** 用例 9：删除空二级类别成功。 */
    @Test
    void 删除空类别成功() throws Exception {
        mockMvc.perform(delete("/api/seller/categories/" + childId)
                        .header("X-Token", login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        assertEquals(1, categoryRepository.count());
    }

    // ---------- 商品管理 ----------

    /** 用例 10：改挂类别成功且落库。 */
    @Test
    void 改挂类别成功() throws Exception {
        Product product = createOnSaleProduct();
        Category other = categoryRepository.save(newChild("另一个子类"));
        mockMvc.perform(put("/api/seller/products/" + product.getId() + "/category")
                        .header("X-Token", login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":" + other.getId() + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        assertEquals(other.getId(), productRepository.findById(product.getId()).orElseThrow().getCategoryId());
    }

    /** 用例 11：挂一级类别被拒 → 400「商品必须挂到二级类别」。 */
    @Test
    void 挂一级类别被拒() throws Exception {
        Product product = createOnSaleProduct();
        mockMvc.perform(put("/api/seller/products/" + product.getId() + "/category")
                        .header("X-Token", login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"categoryId\":" + rootId + "}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("商品必须挂到二级类别"));
    }

    /** 用例 12：调库存成功并写流水（操作人/调整前后/变动量可查）。 */
    @Test
    void 调库存成功并写流水() throws Exception {
        Product product = createOnSaleProduct(); // stock=5
        mockMvc.perform(put("/api/seller/products/" + product.getId() + "/stock")
                        .header("X-Token", login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stock\":20,\"reason\":\"补货\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.stock").value(20))
                .andExpect(jsonPath("$.status").value("on_sale"));
        List<StockLog> logs = stockLogRepository.findByProductIdOrderByIdDesc(product.getId());
        // 不数总条数：发布时若补记入库流水，条数会变；只锁定本次调库存这一笔
        StockLog log = logs.stream()
                .filter(l -> l.getChangeAmount() != null && l.getChangeAmount() == 15)
                .findFirst()
                .orElseThrow(() -> new AssertionError("未找到本次调库存 +15 的流水"));
        assertEquals("admin", log.getOperator());
        assertEquals(5, log.getBeforeStock());
        assertEquals(20, log.getAfterStock());
        assertEquals(15, log.getChangeAmount());
        assertEquals("补货", log.getReason());
    }

    /** 用例 13：负库存被拒 → 400「库存不能为负数」。 */
    @Test
    void 负库存被拒() throws Exception {
        Product product = createOnSaleProduct();
        mockMvc.perform(put("/api/seller/products/" + product.getId() + "/stock")
                        .header("X-Token", login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stock\":-3}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("库存不能为负数"));
    }

    /** 用例 14：在售商品调 0 → 自动售罄；补货 ≥1 → 自动回在售（A.1.3-5）。 */
    @Test
    void 调零售罄补货回在售() throws Exception {
        Product product = createOnSaleProduct();
        mockMvc.perform(put("/api/seller/products/" + product.getId() + "/stock")
                        .header("X-Token", login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stock\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("sold_out"));
        mockMvc.perform(put("/api/seller/products/" + product.getId() + "/stock")
                        .header("X-Token", login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stock\":5,\"reason\":\"补货\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("on_sale"));
        // 恒等式（评审整改四）：ΣchangeAmount = 当前库存（夹具已补「夹具入库」初始流水）
        List<StockLog> logs = stockLogRepository.findByProductIdOrderByIdDesc(product.getId());
        int sum = logs.stream().mapToInt(StockLog::getChangeAmount).sum();
        int stock = productRepository.findById(product.getId()).orElseThrow().getStock();
        assertEquals(sum, stock);
        assertTrue(logs.stream().anyMatch(l -> l.getChangeAmount() != null && l.getChangeAmount() == -5));
        assertTrue(logs.stream().anyMatch(l -> l.getChangeAmount() != null && l.getChangeAmount() == 5));
    }

    /** 用例 14b（评审整改四验收）：发布商品后立刻写 1 条「发布入库」流水，ΣchangeAmount = 当前库存。 */
    @Test
    void 发布商品写初始流水() throws Exception {
        mockMvc.perform(post("/api/seller/products")
                        .header("X-Token", login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"发布流水商品\",\"categoryId\":" + childId
                                + ",\"description\":\"x\",\"price\":29.9,\"stock\":7}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));

        Product product = productRepository.findAll().get(0);
        List<StockLog> logs = stockLogRepository.findByProductIdOrderByIdDesc(product.getId());
        assertEquals(1, logs.size());
        StockLog log = logs.get(0);
        assertEquals("admin", log.getOperator());
        assertEquals(0, log.getBeforeStock());
        assertEquals(7, log.getAfterStock());
        assertEquals(7, log.getChangeAmount());
        assertEquals("发布入库", log.getReason());
        assertEquals(logs.stream().mapToInt(StockLog::getChangeAmount).sum(),
                productRepository.findById(product.getId()).orElseThrow().getStock(),
                "ΣchangeAmount = 当前库存");
    }

    /** 用例 15：手动下架后可上架，状态回在售且下架痕迹清空。 */
    @Test
    void 下架后可上架() throws Exception {
        Product product = createOnSaleProduct();
        mockMvc.perform(post("/api/seller/products/" + product.getId() + "/offshelf")
                        .header("X-Token", login()))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/seller/products/" + product.getId() + "/onshelf")
                        .header("X-Token", login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        Product saved = productRepository.findById(product.getId()).orElseThrow();
        assertEquals(Product.Status.ON_SALE, saved.getStatus());
        assertEquals(null, saved.getClosedAt());
        assertEquals(null, saved.getOffShelfReason());
    }

    /** 用例 16：在售商品重复上架被拒 → 400「仅「已下架」商品可上架」。 */
    @Test
    void 在售商品上架被拒() throws Exception {
        Product product = createOnSaleProduct();
        mockMvc.perform(post("/api/seller/products/" + product.getId() + "/onshelf")
                        .header("X-Token", login()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("仅「已下架」商品可上架"));
    }

    /** 用例 17：售罄商品直接上架被拒 → 400「售罄商品请先补库存」（走真实链路：库存调 0 自动售罄）。 */
    @Test
    void 售罄商品上架被拒() throws Exception {
        Product product = createOnSaleProduct();
        mockMvc.perform(put("/api/seller/products/" + product.getId() + "/stock")
                        .header("X-Token", login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stock\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("sold_out"));
        mockMvc.perform(post("/api/seller/products/" + product.getId() + "/onshelf")
                        .header("X-Token", login()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("售罄商品请先补库存"));
    }

    /** 用例 18（评审验收）：库存调 0 后，售罄商品仍出现在工作台总览（标 sold_out），且 canPublish 为 true。 */
    @Test
    void 售罄商品仍出现在总览() throws Exception {
        Product product = createOnSaleProduct();
        mockMvc.perform(put("/api/seller/products/" + product.getId() + "/stock")
                        .header("X-Token", login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"stock\":0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("sold_out"));

        mockMvc.perform(get("/api/seller/overview").header("X-Token", login()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.detail.product.id").value(product.getId()))
                .andExpect(jsonPath("$.detail.product.status").value("sold_out"))
                .andExpect(jsonPath("$.canPublish").value(true));
    }

    // ---------- 辅助方法 ----------

    /** 登录卖家账号（admin / seller123，DataInitializer 首次启动创建），返回 token */
    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/seller/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"seller123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    /** 直接落库一个在售商品（挂夹具二级类别，库存 5） */
    private Product createOnSaleProduct() {
        Product product = new Product();
        product.setName("测试商品");
        product.setDescription("测试描述");
        product.setPrice(new BigDecimal("99.90"));
        product.setStock(5);
        product.setCategoryId(childId);
        product.setStatus(Product.Status.ON_SALE);
        productRepository.save(product);
        // 夹具初始流水（评审整改四）：口径 ΣchangeAmount 恒等于当前库存，直接落库的夹具也要补一笔
        StockLog initialLog = new StockLog();
        initialLog.setProductId(product.getId());
        initialLog.setOperator("SYSTEM");
        initialLog.setBeforeStock(0);
        initialLog.setAfterStock(5);
        initialLog.setChangeAmount(5);
        initialLog.setReason("夹具入库");
        stockLogRepository.save(initialLog);
        return product;
    }

    /** 在夹具一级类别下新建一个二级类别 */
    private Category newChild(String name) {
        Category child = new Category();
        child.setName(name);
        child.setParentId(rootId);
        return categoryRepository.save(child);
    }
}
