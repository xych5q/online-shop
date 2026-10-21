package com.group1.onlineshop.config;

import com.group1.onlineshop.entity.Category;
import com.group1.onlineshop.entity.Intent;
import com.group1.onlineshop.entity.Product;
import com.group1.onlineshop.entity.ProductImage;
import com.group1.onlineshop.repository.CategoryRepository;
import com.group1.onlineshop.repository.IntentRepository;
import com.group1.onlineshop.repository.ProductImageRepository;
import com.group1.onlineshop.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 旧数据迁移（挑战需求 A · A.2.4-3，需求方指定 <b>B 方案：只读归档</b>）。
 *
 * <p><b>需求方口径</b>：旧系统（口令码时代）正在售卖的商品、排队中的意向、已有历史档案，
 * 上线切换时<b>只读归档</b>——卖家后台可查，<b>不挂到新账号下，不迁移，不复活旧队列</b>。
 *
 * <p><b>执行条件</b>：仅在检测到旧数据时执行一次。判定依据是
 * 「存在 {@code code} 非空的意向」（口令码时代的特征），
 * 迁移完成后这些 code 保留原值以便备查，故不会重复触发。
 *
 * <p><b>具体动作</b>：
 * <ol>
 *   <li>建立归档类别：一级「未分类」+ 其下二级「历史商品」</li>
 *   <li>商品：{@code stock} 为空补 1；{@code categoryId} 为空挂到「历史商品」；
 *       在售 / 已冻结的统一置为已下架（{@code offShelfReason=MANUAL}），不再参与交易</li>
 *   <li>把旧单图字段 {@code image} 迁移为一条 {@code ProductImage}（sortOrder=0，即主图）</li>
 *   <li>意向：全部置为 {@code ARCHIVED}，{@code buyerId} 保持 null</li>
 * </ol>
 *
 * <p>注意：本类<b>不删除任何数据</b>，只做状态与关联的回填，符合「不破坏迭代 1 成果」的要求。
 */
@Slf4j
@Component
@Order(20) // 在 DataInitializer（创建卖家账号）之后执行
@RequiredArgsConstructor
public class MigrationRunner implements CommandLineRunner {

    /** 归档类别名 */
    private static final String ARCHIVE_ROOT_NAME = "未分类";
    private static final String ARCHIVE_CHILD_NAME = "历史商品";

    private final ProductRepository productRepository;
    private final IntentRepository intentRepository;
    private final CategoryRepository categoryRepository;
    private final ProductImageRepository productImageRepository;

    @Override
    @Transactional
    public void run(String... args) {
        List<Intent> legacyIntents = intentRepository.findAll().stream()
                .filter(i -> i.getCode() != null && !i.getCode().isBlank())
                .filter(i -> i.getStatus() != Intent.Status.ARCHIVED)
                .toList();

        List<Product> productsNeedBackfill = productRepository.findAll().stream()
                .filter(p -> p.getStock() == null || p.getCategoryId() == null)
                .toList();

        if (legacyIntents.isEmpty() && productsNeedBackfill.isEmpty()) {
            log.info("[迁移] 未检测到旧数据，跳过");
            return;
        }

        log.info("[迁移] 检测到旧数据：{} 条口令码意向、{} 件需回填的商品，按 B 方案只读归档",
                legacyIntents.size(), productsNeedBackfill.size());

        // 1) 归档类别
        Category archiveChild = ensureArchiveCategory();

        // 2) 商品回填 + 归档
        int archivedProducts = 0;
        for (Product p : productRepository.findAll()) {
            boolean changed = false;
            if (p.getStock() == null) {
                p.setStock(1);
                changed = true;
            }
            if (p.getCategoryId() == null) {
                p.setCategoryId(archiveChild.getId());
                changed = true;
            }
            // 旧系统在售 / 冻结的商品，切换后不再参与交易，统一归档为已下架
            if (p.getStatus() == Product.Status.ON_SALE || p.getStatus() == Product.Status.FROZEN) {
                p.setStatus(Product.Status.OFF_SHELF);
                p.setOffShelfReason("MANUAL");
                if (p.getClosedAt() == null) {
                    p.setClosedAt(java.time.LocalDateTime.now());
                }
                archivedProducts++;
                changed = true;
            }
            if (changed) {
                productRepository.save(p);
            }

            // 3) 旧单图 -> ProductImage 第一条（主图）
            if (p.getImage() != null && !p.getImage().isBlank()
                    && productImageRepository.countByProductId(p.getId()) == 0) {
                ProductImage img = new ProductImage();
                img.setProductId(p.getId());
                img.setFilename(p.getImage());
                img.setSortOrder(0);
                productImageRepository.save(img);
            }
        }

        // 4) 意向归档
        for (Intent i : legacyIntents) {
            i.setStatus(Intent.Status.ARCHIVED);
            i.setBuyerId(null); // B 方案：不挂到新账号下
        }
        intentRepository.saveAll(legacyIntents);

        log.info("[迁移] 完成：归档商品 {} 件、归档意向 {} 条；旧数据卖家后台可查，不进新队列、不可交易",
                archivedProducts, legacyIntents.size());
    }

    /** 确保「未分类 / 历史商品」两级类别存在，返回二级类别 */
    private Category ensureArchiveCategory() {
        Category root = categoryRepository.findByParentIdAndName(null, ARCHIVE_ROOT_NAME)
                .orElseGet(() -> {
                    Category c = new Category();
                    c.setName(ARCHIVE_ROOT_NAME);
                    c.setParentId(null);
                    return categoryRepository.save(c);
                });

        return categoryRepository.findByParentIdAndName(root.getId(), ARCHIVE_CHILD_NAME)
                .orElseGet(() -> {
                    Category c = new Category();
                    c.setName(ARCHIVE_CHILD_NAME);
                    c.setParentId(root.getId());
                    return categoryRepository.save(c);
                });
    }
}
