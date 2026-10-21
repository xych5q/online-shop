package com.group1.onlineshop.service;

import com.group1.onlineshop.entity.Intent;
import com.group1.onlineshop.entity.Product;
import com.group1.onlineshop.exception.BizException;
import com.group1.onlineshop.repository.IntentRepository;
import com.group1.onlineshop.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 交易流转服务（队列与交易域；文件独占 owner 王振涛，见 .github/CODEOWNERS）。
 *
 * <p>迭代 2（挑战需求 A）口径，逐条对应 Wiki《状态机v2（迭代2）》：
 * <ul>
 *   <li><b>W-4 严格 FIFO</b>：队首<b>自动</b>进入交易，卖家不可跳过队首挑人（A.2.3-9），
 *       不存在「被跳过」态；冻结触发条件见 {@link #enterDeal(Product)}</li>
 *   <li><b>W-6 库存扣减</b>：只在标记 {@code success} 时扣 1；提交意向不扣、进入交易不扣（A.1.3-2）</li>
 *   <li><b>W-5 售罄失效</b>：扣减后 {@code stock==0} → 商品转 {@code SOLD_OUT}，
 *       队列中未成交意向全部置 {@code SOLD_OUT}（售罄失效），补货不复活（A.1.3-3 / A.1.3-6）</li>
 *   <li><b>成交不等于下架</b>（A.1.3-4，SRS 4.1）：扣减后 {@code stock>0} → 商品<b>回在售继续卖</b>，
 *       队列<b>不清空</b>，队首自动进入下一笔交易。库存 3 件的商品第 1 笔成交就进历史是错误行为</li>
 *   <li><b>C6 口令码废弃</b>：重新排队不再沿用口令码，新记录 {@code code=null}，沿用账号与电话快照</li>
 *   <li><b>A.2.2-9</b>：买家无取消入口，故本服务不再提供 {@code abortDealByBuyer}</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class TradeService {

    private final IntentRepository intentRepository;
    private final ProductRepository productRepository;

    /**
     * 队首自动进入交易（严格 FIFO，卖家不可挑人 —— A.2.3-9）。
     *
     * <p><b>冻结触发条件（T2）</b>：取该商品 {@code QUEUED} 队首（{@code submittedAt} 升序、{@code id} 升序兜底）
     * 置 {@code IN_TRANSACTION}，同一事务内商品置 {@code FROZEN} + {@code frozenBy='auto'} + {@code frozenAt=now}。
     *
     * <p><b>自动触发时机</b>：
     * <ol>
     *   <li>⚠️ <b>W-4 挂起</b>：买家登录态提交意向后立即进交易 —— 与「冻结期间不接收新意向」「队列必须能形成」冲突，
     *       待组长 / 需求方裁定（见 {@code IntentService#submit} 注释）。<b>挂起期间首次进入交易仍由卖家在后台触发</b></li>
     *   <li>✅ 卖家标记 {@code result=fail} 且队列仍有排队意向 → 下一位自动递补（T5，Q7 裁定，无争议）</li>
     *   <li>✅ 卖家标记 {@code result=success} 且扣减后 {@code stock>0} → 商品回在售后队首自动进下一笔（T6a，Q7 裁定，无争议）</li>
     * </ol>
     *
     * @param product 目标商品（调用方保证已加载且状态允许）
     * @throws BizException 队列为空 → 400「当前没有排队的买家」
     */
    @Transactional
    public void enterDeal(Product product) {
        List<Intent> queue = queue(product.getId());
        if (queue.isEmpty()) {
            throw new BizException("当前没有排队的买家");
        }
        Intent head = queue.get(0);
        head.setStatus(Intent.Status.IN_TRANSACTION);
        intentRepository.save(head);
        product.setStatus(Product.Status.FROZEN);
        product.setFrozenBy("auto");
        product.setFrozenAt(LocalDateTime.now());
        productRepository.save(product);
    }

    /**
     * 该商品当前是否存在进行中的交易（用于提交意向后判断是否立即触发 T2，W-4）。
     */
    @Transactional(readOnly = true)
    public boolean hasActiveDeal(Long productId) {
        return intentRepository.findByProductIdAndStatus(productId, Intent.Status.IN_TRANSACTION).isPresent();
    }

    /**
     * 标记交易结果（W-5 / W-6）。
     *
     * <p>{@code success}：当前意向 → {@code SUCCESS}（{@code disposed=true}）；商品 {@code stock = stock - 1}；随后
     * <ul>
     *   <li>{@code stock > 0}（<b>T6a</b>）→ 商品回 {@code ON_SALE}（清空 {@code frozenBy}/{@code frozenAt}），
     *       <b>队列不清空</b>，队列有人则队首自动进入下一笔交易</li>
     *   <li>{@code stock == 0}（<b>T6b</b>）→ 商品转 {@code SOLD_OUT}（{@code offShelfReason='SOLD_OUT'}、
     *       {@code closedAt=now}），队列中未成交意向全部置 {@code SOLD_OUT}（售罄失效，补货不复活）</li>
     * </ul>
     *
     * <p>{@code fail}：当前意向 → {@code FAILED}（{@code disposed=false}，待卖家处置）；
     * 队列有人 → 下一位自动递补（商品保持 {@code FROZEN/auto}，{@code frozenAt} 刷新）；
     * 队列空 → 商品回 {@code ON_SALE} 并清空 {@code frozenBy}/{@code frozenAt}。
     *
     * @param product 目标商品
     * @param result  {@code success} / {@code fail}，其他值 400「参数错误」
     * @throws BizException 无进行中交易 → 400「当前没有进行中的交易」
     */
    @Transactional
    public void markResult(Product product, String result) {
        Intent deal = intentRepository
                .findByProductIdAndStatus(product.getId(), Intent.Status.IN_TRANSACTION)
                .orElseThrow(() -> new BizException("当前没有进行中的交易"));

        if ("success".equals(result)) {
            deal.setStatus(Intent.Status.SUCCESS);
            deal.setDisposed(true);
            intentRepository.save(deal);

            int left = (product.getStock() == null ? 0 : product.getStock()) - 1;
            product.setStock(left);

            if (left > 0) {
                // T6a：成交不等于下架——商品回在售继续卖，队列不清空
                product.setStatus(Product.Status.ON_SALE);
                product.setFrozenBy(null);
                product.setFrozenAt(null);
                productRepository.save(product);
                if (!queue(product.getId()).isEmpty()) {
                    enterDeal(product); // 队首自动进入下一笔交易
                }
            } else {
                // T6b：库存归零 → 售罄下架，队列未成交意向置售罄失效（I6）
                product.setStatus(Product.Status.SOLD_OUT);
                product.setOffShelfReason("SOLD_OUT");
                product.setClosedAt(LocalDateTime.now());
                productRepository.save(product);
                for (Intent it : queue(product.getId())) {
                    it.setStatus(Intent.Status.SOLD_OUT);
                    it.setDisposed(true);
                    intentRepository.save(it);
                }
            }
            return;
        }

        if ("fail".equals(result)) {
            deal.setStatus(Intent.Status.FAILED);
            deal.setDisposed(false);
            intentRepository.save(deal);
            List<Intent> queue = queue(product.getId());
            if (!queue.isEmpty()) {
                // T5：下一位自动递补进入交易，商品保持冻结
                Intent next = queue.get(0);
                next.setStatus(Intent.Status.IN_TRANSACTION);
                intentRepository.save(next);
                product.setFrozenBy("auto");
                product.setFrozenAt(LocalDateTime.now());
                productRepository.save(product);
            } else {
                // T4：队列无人 → 商品自动恢复在售
                product.setStatus(Product.Status.ON_SALE);
                product.setFrozenBy(null);
                product.setFrozenAt(null);
                productRepository.save(product);
            }
            return;
        }

        throw new BizException("参数错误");
    }

    /**
     * 卖家处置失败意向：作废 / 重新排队（I8 / I9）。
     *
     * <p>{@code void}：仅置 {@code disposed=true}，彻底结束。
     * <br>{@code requeue}：新建一条 {@code QUEUED} 记录，<b>沿用原账号 {@code buyerId} 与电话快照</b>，
     * {@code code=null}（<b>口令码机制已整体废弃，不再生成、不再沿用</b>，C6），{@code submittedAt=now}
     * 排到队尾，{@code requeuedFrom} 指向原意向 id，{@code disposed=false}；原记录置 {@code disposed=true} 保留流水。
     *
     * @param intent 待处置的失败意向
     * @param action {@code void} / {@code requeue}，其他值 400「参数错误」
     */
    @Transactional
    public void disposeFailed(Intent intent, String action) {
        if (!"void".equals(action) && !"requeue".equals(action)) {
            throw new BizException("参数错误");
        }
        if (intent.getStatus() != Intent.Status.FAILED || intent.isDisposed()) {
            throw new BizException("该意向无需处理");
        }
        Product product = productRepository.findById(intent.getProductId()).orElse(null);
        if (product == null || product.getStatus() == Product.Status.OFF_SHELF
                || product.getStatus() == Product.Status.SOLD_OUT) {
            throw new BizException("商品已下架，无法处理");
        }
        intent.setDisposed(true);
        if ("requeue".equals(action)) {
            // 先落库原记录的 disposed，再插入新记录
            intentRepository.saveAndFlush(intent);
            Intent requeued = new Intent();
            requeued.setProductId(intent.getProductId());
            requeued.setBuyerId(intent.getBuyerId());   // 沿用原账号（A.2.3-8：按账号识别）
            requeued.setPhone(intent.getPhone());       // 沿用提交时的电话快照
            requeued.setCode(null);                     // 口令码已废弃，不再生成
            requeued.setSubmittedAt(LocalDateTime.now()); // 刷新提交时间 → 排到队尾
            requeued.setStatus(Intent.Status.QUEUED);
            requeued.setRequeuedFrom(intent.getId());
            requeued.setDisposed(false);
            intentRepository.save(requeued);
        } else {
            intentRepository.save(intent);
        }
    }

    /** 某商品当前的排队队列（QUEUED），按提交时间升序、id 升序兜底 */
    private List<Intent> queue(Long productId) {
        return intentRepository.findByProductIdAndStatusOrderBySubmittedAtAscIdAsc(productId, Intent.Status.QUEUED);
    }
}
