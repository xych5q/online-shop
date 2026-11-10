package com.group1.onlineshop;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.group1.onlineshop.entity.Buyer;
import com.group1.onlineshop.entity.Category;
import com.group1.onlineshop.entity.Product;
import com.group1.onlineshop.entity.ProductImage;
import com.group1.onlineshop.repository.BuyerRepository;
import com.group1.onlineshop.repository.CategoryRepository;
import com.group1.onlineshop.repository.IntentRepository;
import com.group1.onlineshop.repository.ProductImageRepository;
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

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 浏览与搜索域 + 买家注册校验 HTTP 层测试（评审整改五 · L-5 补充）。
 * 覆盖：列表分页与排序、分页边界、类别筛选、关键词搜索（名称/描述）、
 * 商品详情与多图主图、富文本清洗（<script> 剥离）、注册校验（密码强度/手机二次确认/用户名唯一）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class CatalogApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private ProductImageRepository productImageRepository;

    @Autowired
    private CategoryRepository categoryRepository;

    @Autowired
    private BuyerRepository buyerRepository;

    @Autowired
    private IntentRepository intentRepository;

    @Autowired
    private StockLogRepository stockLogRepository;

    private Long childId;

    /** 每个用例前清空业务数据并建二级类别夹具 */
    @BeforeEach
    void cleanUp() {
        stockLogRepository.deleteAll();
        intentRepository.deleteAll();
        productImageRepository.deleteAll();
        productRepository.deleteAll();
        buyerRepository.deleteAll();
        categoryRepository.deleteAll();
        Category root = new Category();
        root.setName("测试分类");
        root.setParentId(null);
        root = categoryRepository.save(root);
        Category child = new Category();
        child.setName("测试子类");
        child.setParentId(root.getId());
        childId = categoryRepository.save(child).getId();
    }

    /** 用例 1：列表按创建时间倒序，page 从 1 起、每页默认 12 条。 */
    @Test
    void 列表倒序且每页12条() throws Exception {
        for (int i = 1; i <= 15; i++) {
            newProduct("商品" + String.format("%02d", i), null, "普通描述" + i);
        }
        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(15))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.pageSize").value(12))
                .andExpect(jsonPath("$.items.length()").value(12))
                .andExpect(jsonPath("$.items[0].name").value("商品15"))   // 倒序：最新在前
                .andExpect(jsonPath("$.items[11].name").value("商品04"));
        mockMvc.perform(get("/api/products?page=2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(3))
                .andExpect(jsonPath("$.items[0].name").value("商品03"));
    }

    /** 用例 2：分页边界——page=0 等价第 1 页；page 超出总页数返回空列表。 */
    @Test
    void 分页边界() throws Exception {
        newProduct("唯一商品", null, "描述");
        mockMvc.perform(get("/api/products?page=0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.page").value(1));
        mockMvc.perform(get("/api/products?page=99"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items.length()").value(0));
    }

    /** 用例 3：类别筛选只返回该二级类别下的商品。 */
    @Test
    void 类别筛选() throws Exception {
        Category other = new Category();
        other.setName("另一子类");
        other.setParentId(categoryRepository.findByParentIdIsNullOrderByIdAsc().get(0).getId());
        other = categoryRepository.save(other);
        newProduct("数码商品", childId, "描述A");
        newProduct("图书商品", other.getId(), "描述B");
        mockMvc.perform(get("/api/products?categoryId=" + childId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].name").value("数码商品"));
    }

    /** 用例 4：关键词命中商品名称。 */
    @Test
    void 关键词命中名称() throws Exception {
        newProduct("无线机械键盘", null, "普通描述");
        newProduct("有线鼠标", null, "普通描述");
        mockMvc.perform(get("/api/products?keyword=键盘"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].name").value("无线机械键盘"));
    }

    /** 用例 5：关键词命中商品描述。 */
    @Test
    void 关键词命中描述() throws Exception {
        newProduct("键盘A", null, "电竞游戏外设首选");
        newProduct("键盘B", null, "办公静音舒适");
        mockMvc.perform(get("/api/products?keyword=电竞"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].name").value("键盘A"));
    }

    /** 用例 6：商品详情含富文本描述与全部图片，主图取 sortOrder 最小的一张。 */
    @Test
    void 详情与多图主图() throws Exception {
        Product product = newProduct("多图商品", null, "详情商品");
        product.setRichDescription("<b>富文本详情</b>");
        productRepository.save(product);
        newImage(product.getId(), "main.png", 0);
        newImage(product.getId(), "second.png", 1);

        mockMvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.richDescription").value("<b>富文本详情</b>"))
                .andExpect(jsonPath("$.product.images.length()").value(2))
                .andExpect(jsonPath("$.product.images[0]").value(containsString("main.png")))
                .andExpect(jsonPath("$.product.images[1]").value(containsString("second.png")))
                .andExpect(jsonPath("$.product.imageUrl").value(containsString("main.png")));
    }

    /** 用例 7：富文本清洗——发布带 <script> 的富文本，详情返回时 script 已被剥掉。 */
    @Test
    void 富文本script被剥离() throws Exception {
        mockMvc.perform(post("/api/seller/products")
                        .header("X-Token", login())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"清洗商品\",\"categoryId\":" + childId
                                + ",\"description\":\"x\",\"price\":9.9,\"stock\":1,"
                                + "\"richDescription\":\"<p>好货</p><script>alert(1)</script>\"}"))
                .andExpect(status().isOk());

        Product product = productRepository.findAll().get(0);
        mockMvc.perform(get("/api/products/" + product.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.product.richDescription").value(containsString("<p>好货</p>")))
                .andExpect(jsonPath("$.product.richDescription").value(not(containsString("script"))))
                .andExpect(jsonPath("$.product.richDescription").value(not(containsString("alert"))));
    }

    /** 用例 8：买家注册校验——弱密码、手机二次确认不一致、用户名重复均被拒。 */
    @Test
    void 注册校验() throws Exception {
        // 弱密码（纯数字，无字母）
        mockMvc.perform(post("/api/buyers/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"buyer001\",\"password\":\"12345678\",\"phone\":\"13800000001\",\"phoneConfirm\":\"13800000001\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value(containsString("密码")));
        // 手机两次输入不一致
        mockMvc.perform(post("/api/buyers/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"buyer001\",\"password\":\"pass1234\",\"phone\":\"13800000001\",\"phoneConfirm\":\"13800000002\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("两次输入的联系电话不一致"));
        // 正常注册成功后，同名再注册被拒（用户名唯一）
        mockMvc.perform(post("/api/buyers/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"buyer001\",\"password\":\"pass1234\",\"phone\":\"13800000001\",\"phoneConfirm\":\"13800000001\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/buyers/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"buyer001\",\"password\":\"pass1234\",\"phone\":\"13900000001\",\"phoneConfirm\":\"13900000001\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("用户名已被注册"));
    }

    /** 用例：裁决 C11——卖家手动停售（manual 冻结）不进列表，也不计入 total；交易中（auto）保留。 */
    @Test
    void 手动停售不进列表也不计总数() throws Exception {
        newProduct("在售商品", null, "描述");
        Product auto = newProduct("交易中商品", null, "描述");
        auto.setStatus(Product.Status.FROZEN);
        auto.setFrozenBy("auto");
        productRepository.save(auto);
        Product manual = newProduct("卖家停售商品", null, "描述");
        manual.setStatus(Product.Status.FROZEN);
        manual.setFrozenBy("manual");
        productRepository.save(manual);

        mockMvc.perform(get("/api/products"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(2))
                .andExpect(jsonPath("$.totalPages").value(1))
                .andExpect(jsonPath("$.items.length()").value(2))
                .andExpect(jsonPath("$.items[*].name", not(hasItem("卖家停售商品"))));
    }

    // ---------- 辅助方法 ----------

    /** 登录卖家账号，返回 token */
    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/seller/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"seller123\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).get("token").asText();
    }

    /** 直接落库一个在售商品（测试夹具） */
    private Product newProduct(String name, Long categoryId, String description) {
        Product product = new Product();
        product.setName(name);
        product.setDescription(description);
        product.setPrice(new BigDecimal("99.90"));
        product.setStock(5);
        product.setCategoryId(categoryId);
        product.setStatus(Product.Status.ON_SALE);
        return productRepository.save(product);
    }

    /** 直接落库一张商品图片（测试夹具） */
    private void newImage(Long productId, String filename, int sortOrder) {
        ProductImage image = new ProductImage();
        image.setProductId(productId);
        image.setFilename(filename);
        image.setSortOrder(sortOrder);
        productImageRepository.save(image);
    }
}
