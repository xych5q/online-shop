package com.group1.onlineshop.service;

import com.group1.onlineshop.entity.Buyer;
import com.group1.onlineshop.entity.Favorite;
import com.group1.onlineshop.entity.Product;
import com.group1.onlineshop.entity.ProductImage;
import com.group1.onlineshop.exception.BizException;
import com.group1.onlineshop.repository.FavoriteRepository;
import com.group1.onlineshop.repository.ProductImageRepository;
import com.group1.onlineshop.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 收藏服务（迭代 3 · L3-5）。
 *
 * <p>需求依据（挑战需求 B SRS）：
 * <ul>
 *   <li>FR-D9 收藏<b>按账号存储</b>、换设备一致；收藏是<b>独立列表</b>，不并入订单历史；
 *       商品下架 / 售罄后收藏<b>保留并标注</b>，不自动消失</li>
 *   <li>G15 同一商品已在收藏则<b>去重、不重复报错</b></li>
 *   <li>FR-D11 购物车转收藏若已收藏同样去重（由购物车域调用本服务的 add 实现）</li>
 * </ul>
 *
 * <p>边界：只读写 {@code favorite} 与只读 {@code product}，不改动任何实体与仓储；
 * 商品状态采用两态口径（在售 / 已下架），售罄 = 已下架 + offShelfReason=SOLD_OUT（L3-1）。
 */
@Service
@RequiredArgsConstructor
public class FavoriteService {

    private final FavoriteRepository favoriteRepository;
    private final ProductRepository productRepository;
    private final ProductImageRepository productImageRepository;
    private final BuyerService buyerService;

    /**
     * 当前登录买家的收藏列表。
     *
     * <p>规则：需买家鉴权（401「未登录或登录已失效」）；按收藏时间倒序、同一时间按 id 倒序兜底；
     * 已下架 / 售罄商品<b>仍返回</b>，由 status + offShelfReason + available 标注，便于前端置灰。
     *
     * <p>返回：{ok, total, items[]}；每项
     * {productId, name, price, imageUrl, status, offShelfReason, available, favoritedAt}。
     */
    public Map<String, Object> list(String token) {
        Buyer buyer = buyerService.requireAuth(token);
        List<Favorite> favorites = new ArrayList<>(
                favoriteRepository.findAllByBuyerIdOrderByCreatedAtDesc(buyer.getId()));
        // 仓储只按 createdAt 排序；同一毫秒内连续收藏时补 id 倒序兜底，保证列表稳定
        favorites.sort(Comparator.comparing(Favorite::getCreatedAt).reversed()
                .thenComparing(Favorite::getId, Comparator.reverseOrder()));

        List<Map<String, Object>> items = new ArrayList<>();
        for (Favorite favorite : favorites) {
            Product product = productRepository.findById(favorite.getProductId()).orElse(null);
            if (product == null) {
                continue; // 商品不存在（当前无删除商品功能，防御性跳过）
            }
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("productId", product.getId());
            item.put("name", product.getName());
            item.put("price", product.getPrice());
            item.put("imageUrl", mainImageUrl(product));
            item.put("status", product.getStatus().name().toLowerCase());
            item.put("offShelfReason", product.getStatus() == Product.Status.OFF_SHELF
                    ? product.getOffShelfReason() : null);
            // 前端置灰/按钮显隐用：只有「在售」可下单，已下架（含售罄）不可下单但保留收藏
            item.put("available", product.getStatus() == Product.Status.ON_SALE);
            item.put("favoritedAt", favorite.getCreatedAt());
            items.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("total", items.size());
        result.put("items", items);
        return result;
    }

    /**
     * 收藏商品（幂等）。
     *
     * <p>规则：需买家鉴权；productId 缺失 → 400「请选择要收藏的商品」；商品不存在 → 404「商品不存在」；
     * 同一账号已收藏 → 直接返回成功，<b>不重复插入、不报错</b>（G15）。
     */
    public Map<String, Object> add(String token, Long productId) {
        Buyer buyer = buyerService.requireAuth(token);
        if (productId == null) {
            throw new BizException("请选择要收藏的商品");
        }
        if (!productRepository.existsById(productId)) {
            throw new BizException("商品不存在", 404);
        }
        if (favoriteRepository.existsByBuyerIdAndProductId(buyer.getId(), productId)) {
            return ok(); // G15：重复收藏去重，不报错
        }
        Favorite favorite = new Favorite();
        favorite.setBuyerId(buyer.getId());
        favorite.setProductId(productId);
        favorite.setCreatedAt(LocalDateTime.now());
        favoriteRepository.save(favorite);
        return ok();
    }

    /**
     * 取消收藏（幂等）。
     *
     * <p>规则：需买家鉴权；只删当前账号自己的收藏；未收藏时同样返回成功，便于前端重复点击。
     */
    public Map<String, Object> remove(String token, Long productId) {
        Buyer buyer = buyerService.requireAuth(token);
        favoriteRepository.findByBuyerIdAndProductId(buyer.getId(), productId)
                .ifPresent(favoriteRepository::delete);
        return ok();
    }

    /** 主图 = sortOrder 最小的一张；无图回退旧单图字段；都没有返回 null（与 CatalogService / SellerService 同口径） */
    private String mainImageUrl(Product product) {
        return productImageRepository.findFirstByProductIdOrderBySortOrderAscIdAsc(product.getId())
                .map(ProductImage::getFilename)
                .or(() -> java.util.Optional.ofNullable(product.getImage()))
                .map(name -> "/uploads/" + name)
                .orElse(null);
    }

    /** 统一成功返回 {ok: true} */
    private Map<String, Object> ok() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        return result;
    }
}
