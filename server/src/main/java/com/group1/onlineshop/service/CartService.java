package com.group1.onlineshop.service;

import com.group1.onlineshop.entity.Buyer;
import com.group1.onlineshop.entity.CartItem;
import com.group1.onlineshop.entity.Product;
import com.group1.onlineshop.exception.BizException;
import com.group1.onlineshop.repository.CartItemRepository;
import com.group1.onlineshop.repository.FavoriteRepository;
import com.group1.onlineshop.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * 购物车服务（迭代 3 · D3-2，买家侧 /api/cart）。
 *
 * <p>需求依据（挑战需求 B SRS）：
 * <ul>
 *   <li>FR-D10 按账号存储、换设备一致；条目结构为<b>名称、单价、数量、小计</b>；
 *       数量可调整；种类数与单种数量<b>无上限</b>；加购阶段<b>不校验库存、不预占、不设价格快照</b>；
 *       售罄 / 已下架商品在<b>结算时</b>校验拦截、提示缺货；无一键清空，只能逐件移除</li>
 *   <li>G10 同一商品重复加入<b>合并为一条并累加数量</b></li>
 *   <li>G11 购物车中售罄或下架商品<b>置灰并标注、不能结算</b></li>
 *   <li>G12 购物车<b>不显示缩略图与总计金额</b>，只列名称、单价、数量、小计</li>
 *   <li>G13 下单成功后已勾选商品移出购物车、未勾选保留（由 OrderService 执行）</li>
 *   <li>FR-D11 / G14 / G15 可勾选多条一次转收藏，转后移出购物车；已在收藏中则去重、不报错</li>
 * </ul>
 *
 * <p>边界：只读写 {@code cart_item}、只读 {@code product}；转收藏复用
 * {@link FavoriteService#add(String, Long)}（收藏侧天然去重，与 B.2.4-3 裁定一致），
 * 不另写收藏表；数量调整不下单、不扣库存。
 */
@Service
@RequiredArgsConstructor
public class CartService {

    /**
     * 单种商品数量上限。需求口径为「无上限」，此处仅作实现护栏：
     * 防止 Integer 累加溢出把数量算成负数，远高于任何真实用途。
     */
    private static final int MAX_QUANTITY = 999_999;

    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final FavoriteRepository favoriteRepository;
    private final BuyerService buyerService;
    private final FavoriteService favoriteService;

    // ---------------------------------------------------------------- 查询

    /**
     * 当前登录买家的购物车。
     *
     * <p>返回：{ok, total, items[]}；每项
     * {productId, name, unitPrice, quantity, subtotal, status, offShelfReason, available,
     * stockSufficient, addedAt}。按加入时间正序（先加入的在上），只列 G12 允许的字段。
     * 商品已下架（含售罄）仍返回，由 status/offShelfReason/available 供前端置灰（G11）。
     */
    public Map<String, Object> list(String token) {
        Buyer buyer = buyerService.requireAuth(token);
        List<CartItem> cartItems = cartItemRepository.findAllByBuyerIdOrderByCreatedAtAsc(buyer.getId());

        List<Map<String, Object>> items = new ArrayList<>();
        for (CartItem cartItem : cartItems) {
            Product product = productRepository.findById(cartItem.getProductId()).orElse(null);
            if (product == null) {
                continue; // 商品不存在（当前无删除商品功能，防御性跳过）
            }
            int quantity = cartItem.getQuantity() == null ? 0 : cartItem.getQuantity();
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("productId", product.getId());
            item.put("name", product.getName());
            item.put("unitPrice", product.getPrice());
            item.put("quantity", quantity);
            item.put("subtotal", product.getPrice().multiply(BigDecimal.valueOf(quantity)));
            item.put("status", product.getStatus().name().toLowerCase());
            item.put("offShelfReason", product.getStatus() == Product.Status.OFF_SHELF
                    ? product.getOffShelfReason() : null);
            // 只有「在售」可结算（G11）；库存是否够另给 stockSufficient，供结算前提示
            item.put("available", product.getStatus() == Product.Status.ON_SALE);
            item.put("stockSufficient", product.getStock() != null && product.getStock() >= quantity);
            item.put("addedAt", cartItem.getCreatedAt());
            items.add(item);
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("total", items.size());
        result.put("items", items);
        return result;
    }

    // ---------------------------------------------------------------- 加购

    /**
     * 加入购物车；同账号同商品合并累加数量（G10）。
     *
     * <p>加购阶段<b>不校验库存、不预占</b>（FR-D10 / B.2.2-4）；只在商品不存在时 404。
     * 数量缺省 1。已下架商品也允许加入，结算时再拦截（G11），避免加购与结算两处口径打架。
     */
    @Transactional
    public Map<String, Object> add(String token, Long productId, Integer quantity) {
        Buyer buyer = buyerService.requireAuth(token);
        if (productId == null) {
            throw new BizException("请选择要加入购物车的商品");
        }
        int addQuantity = quantity == null ? 1 : quantity;
        if (addQuantity <= 0) {
            throw new BizException("数量必须大于 0");
        }
        if (!productRepository.existsById(productId)) {
            throw new BizException("商品不存在", 404);
        }

        Optional<CartItem> existing = cartItemRepository.findByBuyerIdAndProductId(buyer.getId(), productId);
        CartItem cartItem;
        int quantityAfter;
        if (existing.isPresent()) {
            cartItem = existing.get();
            int current = cartItem.getQuantity() == null ? 0 : cartItem.getQuantity();
            quantityAfter = current + addQuantity;
            if (quantityAfter > MAX_QUANTITY) {
                throw new BizException("数量超出上限");
            }
            cartItem.setQuantity(quantityAfter);   // G10：合并为一条并累加
        } else {
            cartItem = new CartItem();
            cartItem.setBuyerId(buyer.getId());
            cartItem.setProductId(productId);
            cartItem.setQuantity(addQuantity);
            cartItem.setCreatedAt(LocalDateTime.now());
            quantityAfter = addQuantity;
        }
        cartItemRepository.save(cartItem);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("productId", productId);
        result.put("quantity", quantityAfter);
        return result;
    }

    // ---------------------------------------------------------------- 数量调整

    /**
     * 调整某个商品的数量（覆盖式，不累加）。
     *
     * <p>规则：必须已在该账号购物车中（否则 404）；数量必须 &gt; 0，移除只能走逐件移除接口；
     * 只改数量，不校验库存、不扣减（FR-D10）。
     */
    @Transactional
    public Map<String, Object> updateQuantity(String token, long productId, Integer quantity) {
        Buyer buyer = buyerService.requireAuth(token);
        if (quantity == null || quantity <= 0) {
            throw new BizException("数量必须大于 0");
        }
        if (quantity > MAX_QUANTITY) {
            throw new BizException("数量超出上限");
        }
        CartItem cartItem = cartItemRepository.findByBuyerIdAndProductId(buyer.getId(), productId)
                .orElseThrow(() -> new BizException("购物车中没有该商品", 404));
        cartItem.setQuantity(quantity);
        cartItemRepository.save(cartItem);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("productId", productId);
        result.put("quantity", quantity);
        return result;
    }

    // ---------------------------------------------------------------- 逐件移除

    /**
     * 逐件移除：只删当前账号自己的条目；不存在同样返回成功（幂等，便于前端重复点击）。
     * 需求明确不做一键清空（SRS 1.2 不做清单）。
     */
    @Transactional
    public Map<String, Object> remove(String token, long productId) {
        Buyer buyer = buyerService.requireAuth(token);
        cartItemRepository.findByBuyerIdAndProductId(buyer.getId(), productId)
                .ifPresent(cartItemRepository::delete);

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("productId", productId);
        return result;
    }

    // ---------------------------------------------------------------- 批量转收藏

    /**
     * 勾选多条一次转收藏（FR-D11）：
     * 逐条把购物车条目转入收藏 → 转后移出购物车（G14）→ 已在收藏中静默去重（G15）。
     *
     * <p>返回 {ok, moved[], alreadyFavorited[], notInCart[]}：前端据此提示
     * 「已转 N 条 / 其中 M 条原本已收藏 / K 条已不在购物车」。全部不在购物车时 400。
     */
    @Transactional
    public Map<String, Object> toFavorites(String token, List<Long> productIds) {
        Buyer buyer = buyerService.requireAuth(token);
        if (productIds == null || productIds.isEmpty()) {
            throw new BizException("请选择要转收藏的商品");
        }

        List<Long> moved = new ArrayList<>();
        List<Long> alreadyFavorited = new ArrayList<>();
        List<Long> notInCart = new ArrayList<>();
        for (Long productId : new LinkedHashSet<>(productIds)) {
            if (productId == null) {
                continue;
            }
            Optional<CartItem> cartItem = cartItemRepository.findByBuyerIdAndProductId(buyer.getId(), productId);
            if (cartItem.isEmpty()) {
                notInCart.add(productId);
                continue;
            }
            boolean favorited = favoriteRepository.existsByBuyerIdAndProductId(buyer.getId(), productId);
            favoriteService.add(token, productId);      // 收藏侧去重，已收藏也不报错（G15）
            cartItemRepository.delete(cartItem.get()); // 转后移出购物车（G14）
            if (favorited) {
                alreadyFavorited.add(productId);
            } else {
                moved.add(productId);
            }
        }
        if (moved.isEmpty() && alreadyFavorited.isEmpty()) {
            throw new BizException("所选商品不在购物车中");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("moved", moved);
        result.put("alreadyFavorited", alreadyFavorited);
        result.put("notInCart", notInCart);
        return result;
    }

    // ---------------------------------------------------------------- 结算前校验

    /**
     * 结算前校验（不下单、不扣库存、不加锁）：给出不可结算与缺货清单。
     *
     * <p>入参形态与下单一致：{items:[{productId,quantity}]}；缺省或空数组时校验整车。
     * 返回 {ok, pass, blocked[], shortages[]}：blocked 为已下架 / 不存在，
     * shortages 为在售但库存不足（required/available）。业务上最终以下单接口的整单拦截为准，
     * 本接口只做提交前的即时提示（G11 / B.2.2-6）。
     */
    public Map<String, Object> validate(String token, List<Map<String, Object>> items) {
        Buyer buyer = buyerService.requireAuth(token);

        TreeMap<Long, Integer> wanted = new TreeMap<>();
        if (items == null || items.isEmpty()) {
            for (CartItem cartItem : cartItemRepository.findAllByBuyerIdOrderByCreatedAtAsc(buyer.getId())) {
                wanted.merge(cartItem.getProductId(),
                        cartItem.getQuantity() == null ? 0 : cartItem.getQuantity(), Integer::sum);
            }
        } else {
            for (Map<String, Object> item : items) {
                Long productId = toLong(item.get("productId"));
                Integer quantity = toInt(item.get("quantity"));
                if (productId == null) {
                    throw new BizException("结算明细缺少 productId");
                }
                if (quantity == null || quantity <= 0) {
                    throw new BizException("数量必须大于 0");
                }
                wanted.merge(productId, quantity, Integer::sum);
            }
        }
        if (wanted.isEmpty()) {
            throw new BizException("购物车为空");
        }

        List<Map<String, Object>> blocked = new ArrayList<>();
        List<Map<String, Object>> shortages = new ArrayList<>();
        for (Map.Entry<Long, Integer> entry : wanted.entrySet()) {
            Product product = productRepository.findById(entry.getKey()).orElse(null);
            if (product == null) {
                blocked.add(Map.of("productId", entry.getKey(), "name", "", "reason", "NOT_FOUND"));
                continue;
            }
            if (product.getStatus() != Product.Status.ON_SALE) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("productId", product.getId());
                row.put("name", product.getName());
                row.put("reason", "OFF_SHELF");
                row.put("offShelfReason", product.getOffShelfReason());
                blocked.add(row);
                continue;
            }
            int stock = product.getStock() == null ? 0 : product.getStock();
            if (stock < entry.getValue()) {
                shortages.add(Map.of(
                        "productId", product.getId(),
                        "name", product.getName(),
                        "required", entry.getValue(),
                        "available", stock));
            }
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("pass", blocked.isEmpty() && shortages.isEmpty());
        result.put("blocked", blocked);
        result.put("shortages", shortages);
        return result;
    }

    private Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private Integer toInt(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }
}
