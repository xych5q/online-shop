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
 * 交易流转服务：进入交易、标记交易结果、处置失败意向。
 * 业务规则逐条翻译自原型 prototype/single-seller-queue-trade/server.js（第 135~221 行交易流转核心），规则未做改动。
 */
@Service
@RequiredArgsConstructor
public class TradeService {

    private final IntentRepository intentRepository;
    private final ProductRepository productRepository;

    /**
     * 与队首买家进入交易（卖家不可挑人）。
     * 业务规则：取该商品排队中意向的队首（按提交时间正序、id 兜底防同一时刻）置为交易中；
     * 商品自动置已冻结、frozenBy='auto'、frozenAt=now；队列为空 → 400「当前没有排队的买家」。
     *
     * @param product 目标商品
     */
    @Transactional
    public void enterDeal(Product product) {
        List<Intent> queue = intentRepository
                .findByProductIdAndStatusOrderBySubmittedAtAscIdAsc(product.getId(), Intent.Status.QUEUED);
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
     * 标记交易结果。
     * 业务规则：当前无进行中的交易 → 400「当前没有进行中的交易」；result 只接受 success / fail，其他值 400「参数错误」。
     * success：胜出意向置成功且 disposed=true，队列剩余排队中意向全部置失败且 disposed=true，商品下架进历史（closedAt=now）；
     * fail：当前意向置失败（disposed=false，待卖家处置）；
     * 队列有人 → 下一位自动递补进入交易（无需卖家再次点击，商品保持冻结）；
     * 队列无人 → 商品自动恢复在售并清空 frozenBy / frozenAt。
     *
     * @param product 目标商品
     * @param result  交易结果 success / fail
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
            for (Intent it : intentRepository
                    .findByProductIdAndStatusOrderBySubmittedAtAscIdAsc(product.getId(), Intent.Status.QUEUED)) {
                it.setStatus(Intent.Status.FAILED);
                it.setDisposed(true);
                intentRepository.save(it);
            }
            product.setStatus(Product.Status.OFF_SHELF);
            product.setClosedAt(LocalDateTime.now());
            productRepository.save(product);
            return;
        }
        if ("fail".equals(result)) {
            deal.setStatus(Intent.Status.FAILED);
            deal.setDisposed(false);
            intentRepository.save(deal);
            List<Intent> queue = intentRepository
                    .findByProductIdAndStatusOrderBySubmittedAtAscIdAsc(product.getId(), Intent.Status.QUEUED);
            if (!queue.isEmpty()) {
                // 队列有人 → 下一位自动递补进入交易，商品保持冻结
                Intent next = queue.get(0);
                next.setStatus(Intent.Status.IN_TRANSACTION);
                intentRepository.save(next);
                product.setFrozenAt(LocalDateTime.now());
                productRepository.save(product);
            } else {
                // 队列无人 → 商品自动恢复在售
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
     * 卖家处置失败意向：作废 / 重新排队。
     * 业务规则：仅处置已失败且未处置过的意向 → 否则 400「该意向无需处理」；
     * 商品已下架时无法处置 → 400「商品已下架，无法处理」；
     * void（作废）：只置 disposed=true；
     * requeue（重新排队）：新建一条排队中意向，沿用原口令码、submittedAt 刷新为当前时间（排到队尾）、
     * requeuedFrom 指向原意向 id、disposed=false，并把原意向置 disposed=true（原记录保留作历史流水）。
     * 因 entity 中 Intent.code 定义了唯一约束（unique=true），两行不能同码：
     * 将已终态的原记录口令码改为带后缀的占位值、新记录沿用原码。
     * 对外行为与原型完全一致——口令码只对未终态意向有效（买家 lookup 只认未终态），历史流水不返回口令码。
     *
     * @param intent 待处置的失败意向
     * @param action void=作废 / requeue=重新排队
     */
    @Transactional
    public void disposeFailed(Intent intent, String action) {
        if (intent.getStatus() != Intent.Status.FAILED || intent.isDisposed()) {
            throw new BizException("该意向无需处理");
        }
        Product product = productRepository.findById(intent.getProductId()).orElse(null);
        if (product == null || product.getStatus() == Product.Status.OFF_SHELF) {
            throw new BizException("商品已下架，无法处理");
        }
        intent.setDisposed(true);
        if ("requeue".equals(action)) {
            String originalCode = intent.getCode();
            intent.setCode(originalCode + "#r" + intent.getId());
            // 先立即落库改码：Hibernate 默认 INSERT 先于 UPDATE 执行，
            // 若不先冲刷，新记录（沿用原码）的 INSERT 会撞上原记录尚未改码的唯一约束
            intentRepository.saveAndFlush(intent);
            Intent requeued = new Intent();
            requeued.setProductId(intent.getProductId());
            requeued.setName(intent.getName());
            requeued.setPhone(intent.getPhone());
            requeued.setCode(originalCode); // 重新排队不生成新口令码
            requeued.setSubmittedAt(LocalDateTime.now()); // 提交时间刷新 → 排到队尾
            requeued.setStatus(Intent.Status.QUEUED);
            requeued.setRequeuedFrom(intent.getId());
            requeued.setDisposed(false);
            intentRepository.save(requeued);
        } else {
            intentRepository.save(intent);
        }
    }
}
