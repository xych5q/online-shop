package com.group1.onlineshop.service;

import com.group1.onlineshop.entity.Buyer;
import com.group1.onlineshop.entity.CartItem;
import com.group1.onlineshop.entity.Order;
import com.group1.onlineshop.entity.OrderAuditLog;
import com.group1.onlineshop.entity.OrderItem;
import com.group1.onlineshop.entity.Product;
import com.group1.onlineshop.entity.Seller;
import com.group1.onlineshop.exception.BizException;
import com.group1.onlineshop.repository.CartItemRepository;
import com.group1.onlineshop.repository.OrderAuditLogRepository;
import com.group1.onlineshop.repository.OrderItemRepository;
import com.group1.onlineshop.repository.OrderRepository;
import com.group1.onlineshop.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 订单服务（迭代 3 · 挑战需求 B 核心，W3-2/W3-3/W3-4）。
 *
 * <p><b>事务边界（2026-11-18 与林初俊 L3-2 定稿）</b>：本服务是库存动作的<b>唯一发起方</b>，
 * 每次库存联动开一个 {@code @Transactional} 包住「改订单状态 + 多次 deduct/restore」；
 * 商品服务三方法（{@link ProductService#deductStock} / {@link ProductService#restoreStock} /
 * {@link ProductService#refreshSaleStatusAfterRestore}）均为 {@code Propagation.MANDATORY}、
 * 不自开事务、内部悲观锁。多件操作一律<b>按 productId 升序</b>依次调用，避免两笔订单交叉加行锁死锁。
 * 扣减与回补只归本服务一人写，商品服务不开事务（G16）。
 *
 * <p><b>异常约定</b>：{@code deductStock} 库存不足抛 {@link BizException}（RuntimeException），
 * 本服务<b>不在事务方法内 catch 后吞掉</b>，否则整单不回滚；需要失败清单的批量取消，
 * 预校验失败直接返回结果对象（无写入），执行阶段异常照抛回滚。
 *
 * <p>状态取值见 {@link Order.Status}（与 develop 的 Order.java C3-1 一致）；
 * 取消窗口：买家 = 前 3 态、商家 = 前 4 态（含开始发货，批量裁定口径 #2）；
 * 批量取消仅商家侧、单接口单事务、全部可取消才执行（G5）；已取消留历史置灰（G4）。
 */
@Service
@RequiredArgsConstructor
public class OrderService {

    /** 状态推进链：严格单向逐级，不可跳级不可回退（B.1.1-6） */
    private static final List<Order.Status> ADVANCE_CHAIN = List.of(
            Order.Status.PLACED, Order.Status.CONFIRMED, Order.Status.PREPARED,
            Order.Status.SHIPPING, Order.Status.COMPLETED);

    /** 买家可取消窗口：前三个状态（B.1.2-2） */
    private static final List<Order.Status> BUYER_CANCELLABLE =
            ADVANCE_CHAIN.subList(0, 3);

    /** 商家可取消窗口：交易完成前的全部状态，含开始发货（批量裁定口径 #2） */
    private static final List<Order.Status> SELLER_CANCELLABLE =
            ADVANCE_CHAIN.subList(0, 4);

    private static final DateTimeFormatter ORDER_NO_FMT = DateTimeFormatter.ofPattern("yyyyMMddHHmmss");

    private final OrderRepository orderRepository;
    private final OrderItemRepository orderItemRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final OrderAuditLogRepository orderAuditLogRepository;
    private final BuyerService buyerService;
    private final SellerService sellerService;
    private final ProductService productService;

    // ---------------------------------------------------------------- 下单 O0

    /**
     * 从购物车勾选多件提交下单（O0 转移，US-26）。
     *
     * <p>同一事务内完成：逐商品校验在售 → 按 productId 升序扣减（任一 {@link BizException}
     * 整单回滚，G16）→ 生成一笔订单 + 多条明细（FR-D7/B.2.3-1）→ 已勾选条目移出购物车、
     * 未勾选保留（G13/B.2.3-6）。扣至 0 的商品由 deductStock 内部自动下架（FR-C4）。
     * 加购阶段不校验库存、不预占、无快照——校验只发生在本方法（B.2.2-4/5）。
     */
    @Transactional
    public Map<String, Object> placeOrder(String token, List<Map<String, Object>> items) {
        Buyer buyer = buyerService.requireAuth(token);
        if (items == null || items.isEmpty()) {
            throw new BizException("下单明细不能为空");
        }

        // 合并同商品条目（与购物车条目唯一约定一致），TreeMap 保证 productId 升序（锁排序）
        TreeMap<Long, Integer> merged = new TreeMap<>();
        for (Map<String, Object> item : items) {
            Long productId = toLong(item.get("productId"));
            Integer quantity = toInt(item.get("quantity"));
            if (productId == null) {
                throw new BizException("下单明细缺少 productId");
            }
            if (quantity == null || quantity <= 0) {
                throw new BizException("下单数量必须大于 0");
            }
            merged.merge(productId, quantity, Integer::sum);
        }

        // 逐商品校验在售并取当前价（价格不可变 FR-C11，读操作不加锁；下架/售罄整单拦截，G11/B.2.3-3）
        BigDecimal totalAmount = BigDecimal.ZERO;
        Map<Long, Product> products = new LinkedHashMap<>();
        for (Map.Entry<Long, Integer> entry : merged.entrySet()) {
            Product product = productRepository.findById(entry.getKey())
                    .orElseThrow(() -> new BizException("商品不存在", 404));
            if (product.getStatus() != Product.Status.ON_SALE) {
                throw new BizException("商品「" + product.getName() + "」已下架，不可结算");
            }
            products.put(entry.getKey(), product);
            totalAmount = totalAmount.add(product.getPrice()
                    .multiply(BigDecimal.valueOf(entry.getValue())));
        }

        // 同一事务：按 productId 升序逐件扣减（任一不足 BizException → 整单回滚，不 catch 吞）
        for (Map.Entry<Long, Integer> entry : merged.entrySet()) {
            productService.deductStock(entry.getKey(), entry.getValue());
        }

        // 生成一笔订单 + 多条明细（整单一套状态）
        Order order = new Order();
        order.setOrderNo(nextOrderNo());
        order.setBuyerId(buyer.getId());
        order.setLocation(buyer.getDefaultLocation());
        order.setTotalAmount(totalAmount);
        order.setStatus(Order.Status.PLACED);
        orderRepository.save(order);

        for (Map.Entry<Long, Integer> entry : merged.entrySet()) {
            OrderItem item = new OrderItem();
            item.setOrderId(order.getId());
            item.setProductId(entry.getKey());
            item.setQuantity(entry.getValue());
            orderItemRepository.save(item);
        }

        // 已勾选条目移出购物车、未勾选保留（G13）
        for (Long productId : merged.keySet()) {
            cartItemRepository.findByBuyerIdAndProductId(buyer.getId(), productId)
                    .ifPresent(cartItemRepository::delete);
        }

        return Map.of(
                "ok", true,
                "orderId", order.getId(),
                "orderNo", order.getOrderNo(),
                "status", order.getStatus().name(),
                "totalAmount", totalAmount);
    }

    // ---------------------------------------------------------------- 买家侧查询

    /** 我的订单/历史：按下单时间倒序、每页 10 条（P5）；已取消保留置灰（G4），无「失败」值（B.1.1-9）。 */
    public Map<String, Object> myOrders(String token, int page) {
        Buyer buyer = buyerService.requireAuth(token);
        Page<Order> result = orderRepository.findAllByBuyerIdOrderByCreatedAtDesc(
                buyer.getId(), PageRequest.of(Math.max(page, 1) - 1, 10));
        return pagePayload(result.getContent(), result.getTotalElements(), Math.max(page, 1));
    }

    // ---------------------------------------------------------------- 买家取消 C1

    /** 买家取消：仅前三个状态可取消（B.1.2-2），不填原因（G2），服务端复检与前端显隐双保险。 */
    @Transactional
    public Map<String, Object> buyerCancel(String token, long orderId) {
        Buyer buyer = buyerService.requireAuth(token);
        Order order = requireOrder(orderId);
        if (!order.getBuyerId().equals(buyer.getId())) {
            throw new BizException("无权操作该订单", 403);
        }
        if (!BUYER_CANCELLABLE.contains(order.getStatus())) {
            throw new BizException(cancelRejectMessage(order));
        }
        cancelOrder(order, buyer.getUsername(), "BUYER");
        return Map.of("ok", true, "orderId", order.getId(), "status", order.getStatus().name());
    }

    // ---------------------------------------------------------------- 商家侧

    /** 商家订单列表：每页 10、下单时间倒序（P5），可选状态筛选。 */
    public Map<String, Object> sellerOrders(String token, int page, String status) {
        sellerService.requireAuth(token);
        Page<Order> result;
        if (status == null || status.isBlank()) {
            result = orderRepository.findAllByOrderByCreatedAtDesc(
                    PageRequest.of(Math.max(page, 1) - 1, 10));
        } else {
            Order.Status parsed = parseStatus(status);
            result = orderRepository.findAllByStatusOrderByCreatedAtDesc(
                    parsed, PageRequest.of(Math.max(page, 1) - 1, 10));
        }
        return pagePayload(result.getContent(), result.getTotalElements(), Math.max(page, 1));
    }

    /**
     * 商家推进（A1~A4）：单向逐级、不可跳级不可回退（B.1.1-6）、全手动（B.1.1-7）；
     * 推进不改库存（前提 1）；写 P2 审计（订单状态推进）。
     */
    @Transactional
    public Map<String, Object> advance(String token, long orderId) {
        Seller seller = sellerService.requireAuth(token);
        Order order = requireOrder(orderId);
        int index = ADVANCE_CHAIN.indexOf(order.getStatus());
        if (index < 0) {
            throw new BizException("已取消订单不可推进");
        }
        if (index >= ADVANCE_CHAIN.size() - 1) {
            throw new BizException("交易已完成，无可推进状态");
        }
        Order.Status from = order.getStatus();
        Order.Status next = ADVANCE_CHAIN.get(index + 1);
        order.setStatus(next);
        orderRepository.save(order);
        audit(order, OrderAuditLog.Action.ADVANCE, from, next, "SELLER", seller.getUsername());
        return Map.of("ok", true, "orderId", order.getId(), "status", next.name());
    }

    /**
     * 商家单笔取消（C2）：交易完成前均可、含开始发货（口径 #2）；不填理由、买家侧不展示（G3）；
     * 回补与买家取消同款同事务。
     */
    @Transactional
    public Map<String, Object> sellerCancel(String token, long orderId) {
        Seller seller = sellerService.requireAuth(token);
        Order order = requireOrder(orderId);
        if (!SELLER_CANCELLABLE.contains(order.getStatus())) {
            throw new BizException(cancelRejectMessage(order));
        }
        cancelOrder(order, seller.getUsername(), "SELLER");
        return Map.of("ok", true, "orderId", order.getId(), "status", order.getStatus().name());
    }

    /**
     * 商家批量取消（C3，G5 五项口径）。
     *
     * <p>执行前一次性预校验：全部存在、非重复、状态 ∈ 前 4 态；任一不可取消 →
     * <b>整批不执行</b>、返回失败清单（orderId+reason），订单状态与库存全部不变（口径 #2）。
     * 全部通过则<b>同一事务</b>内逐单取消 + 按件回补（productId 升序），事务收尾对涉及商品
     * <b>去重后逐个</b> refresh（每个商品恰好一次，统一判断，口径 #3）。
     * 预校验失败无写入直接返回结构化结果；执行阶段任何异常照抛回滚（不 catch 吞）。
     */
    @Transactional
    public Map<String, Object> batchCancel(String token, List<Long> orderIds) {
        Seller seller = sellerService.requireAuth(token);
        if (orderIds == null || orderIds.isEmpty()) {
            throw new BizException("未选择要取消的订单");
        }

        // 预校验：重复 / 不存在 / 状态不允许，全部收集进失败清单（口径 #2）
        Map<Long, String> failed = new LinkedHashMap<>();
        List<Long> distinct = new ArrayList<>();
        for (Long id : orderIds) {
            if (id == null) {
                continue;
            }
            if (distinct.contains(id)) {
                failed.putIfAbsent(id, "DUPLICATE");
                continue;
            }
            distinct.add(id);
        }
        List<Order> cancellable = new ArrayList<>();
        for (Long id : distinct) {
            if (failed.containsKey(id)) {
                continue;
            }
            java.util.Optional<Order> order = orderRepository.findById(id);
            if (order.isEmpty()) {
                failed.put(id, "NOT_FOUND");
            } else if (!SELLER_CANCELLABLE.contains(order.get().getStatus())) {
                failed.put(id, order.get().getStatus().name());
            } else {
                cancellable.add(order.get());
            }
        }
        if (!failed.isEmpty()) {
            List<Map<String, Object>> failedList = failed.entrySet().stream()
                    .map(e -> Map.<String, Object>of("orderId", e.getKey(), "reason", e.getValue()))
                    .collect(Collectors.toList());
            return Map.of("ok", false, "executed", List.of(), "failed", failedList);
        }

        // 全部可取消：同一事务逐单取消 + 回补 + 统一判断恢复
        List<Long> cancelled = new ArrayList<>();
        for (Order order : cancellable) {
            cancelOrder(order, seller.getUsername(), "SELLER");
            cancelled.add(order.getId());
        }
        return Map.of("ok", true, "cancelled", cancelled);
    }

    // ---------------------------------------------------------------- 私有：取消与留痕共用

    /**
     * 取消共用段（调用方已在事务内）：整单置 CANCELLED → 按 productId 升序逐件回补 →
     * 涉及商品去重（TreeMap keySet 天然去重且升序）逐个 refresh → 写 P2 审计（订单取消）。
     */
    private void cancelOrder(Order order, String operatorName, String operatorType) {
        Order.Status from = order.getStatus();
        order.setStatus(Order.Status.CANCELLED);
        orderRepository.save(order);

        TreeMap<Long, Integer> restore = new TreeMap<>();
        for (OrderItem item : orderItemRepository.findByOrderId(order.getId())) {
            restore.merge(item.getProductId(), item.getQuantity(), Integer::sum);
        }
        for (Map.Entry<Long, Integer> entry : restore.entrySet()) {
            productService.restoreStock(entry.getKey(), entry.getValue());
        }
        for (Long productId : restore.keySet()) {
            productService.refreshSaleStatusAfterRestore(productId);
        }

        audit(order, OrderAuditLog.Action.CANCEL, from, Order.Status.CANCELLED, operatorType, operatorName);
    }

    private Order requireOrder(long orderId) {
        return orderRepository.findById(orderId)
                .orElseThrow(() -> new BizException("订单不存在", 404));
    }

    private String cancelRejectMessage(Order order) {
        if (order.getStatus() == Order.Status.COMPLETED) {
            return "交易已完成，不可取消";
        }
        if (order.getStatus() == Order.Status.CANCELLED) {
            return "订单已取消";
        }
        return "开始发货后买家不可取消";
    }

    private void audit(Order order, OrderAuditLog.Action action,
                       Order.Status from, Order.Status to, String operatorType, String operatorName) {
        OrderAuditLog log = new OrderAuditLog();
        log.setOrderId(order.getId());
        log.setOrderNo(order.getOrderNo());
        log.setAction(action);
        log.setFromStatus(from.name());
        log.setToStatus(to.name());
        log.setOperatorType(operatorType);
        log.setOperatorName(operatorName);
        orderAuditLogRepository.save(log);
    }

    /** 订单号：ORD + 时间戳 + 3 位随机（订单表唯一约束兜底，冲突概率可忽略） */
    private String nextOrderNo() {
        return "ORD" + LocalDateTime.now().format(ORDER_NO_FMT)
                + ThreadLocalRandom.current().nextInt(100, 1000);
    }

    /** 列表出参组装：订单 + 明细（名称/单价读商品当前值，FR-C11 不冗余） */
    private Map<String, Object> pagePayload(List<Order> orders, long total, int page) {
        List<Long> orderIds = orders.stream().map(Order::getId).collect(Collectors.toList());
        Map<Long, List<OrderItem>> itemsByOrder = orderItemRepository.findByOrderIdIn(orderIds)
                .stream().collect(Collectors.groupingBy(OrderItem::getOrderId));
        List<Long> productIds = itemsByOrder.values().stream()
                .flatMap(List::stream).map(OrderItem::getProductId).distinct().collect(Collectors.toList());
        Map<Long, Product> products = productRepository.findAllById(productIds).stream()
                .collect(Collectors.toMap(Product::getId, Function.identity()));

        List<Map<String, Object>> items = orders.stream().map(order -> {
            List<Map<String, Object>> detail = itemsByOrder
                    .getOrDefault(order.getId(), List.of()).stream()
                    .map(oi -> {
                        Product product = products.get(oi.getProductId());
                        return Map.<String, Object>of(
                                "productId", oi.getProductId(),
                                "productName", product == null ? "" : product.getName(),
                                "unitPrice", product == null ? BigDecimal.ZERO : product.getPrice(),
                                "quantity", oi.getQuantity());
                    })
                    .collect(Collectors.toList());
            return Map.<String, Object>of(
                    "orderId", order.getId(),
                    "orderNo", order.getOrderNo(),
                    "status", order.getStatus().name(),
                    "totalAmount", order.getTotalAmount(),
                    "createdAt", order.getCreatedAt().toString(),
                    "items", detail);
        }).collect(Collectors.toList());

        return Map.of("ok", true, "page", page, "pageSize", 10, "total", total, "items", items);
    }

    private Order.Status parseStatus(String status) {
        try {
            return Order.Status.valueOf(status.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            throw new BizException("非法状态筛选值：" + status);
        }
    }

    private Long toLong(Object value) {
        return value instanceof Number number ? number.longValue() : null;
    }

    private Integer toInt(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }
}
