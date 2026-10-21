package com.group1.onlineshop.repository;

import com.group1.onlineshop.entity.Intent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * 意向仓储。
 *
 * <p>挑战需求 A 升级要点：意向改绑买家账号（A.2.1-2），口令码机制整体废弃（C6 裁决）。
 * 因此 {@link #findByCode(String)} <b>保留但不供业务使用</b>——口令码不再生成，
 * 三个凭码接口（查询位次 / 修改信息 / 撤销）将随队列模块改造一并删除。
 *
 * <p>方法兼容说明：原有 5 个方法一个未删。
 */
public interface IntentRepository extends JpaRepository<Intent, Long> {

    // ── 原有方法（基线遗留，保留不动） ─────────────────────────────────────

    /** 队列：某商品下排队中的意向，按提交时间正序（id 兜底防同一时刻） */
    List<Intent> findByProductIdAndStatusOrderBySubmittedAtAscIdAsc(Long productId, Intent.Status status);

    /** 当前交易对象 */
    Optional<Intent> findByProductIdAndStatus(Long productId, Intent.Status status);

    /** 待卖家处置的失败意向 */
    List<Intent> findByProductIdAndStatusAndDisposedFalseOrderBySubmittedAtAsc(Long productId, Intent.Status status);

    /** 凭口令码查意向（<b>机制已废弃</b>，保留仅供旧数据归档备查） */
    Optional<Intent> findByCode(String code);

    /** 某商品下的全部意向（历史流水） */
    List<Intent> findByProductIdOrderBySubmittedAtAsc(Long productId);

    // ── 挑战需求 A 新增 ──────────────────────────────────────────────────

    /**
     * 买家侧「我的历史」（A.2.2）：按提交时间倒序分页（A.2.2-3 / A.2.2-8）。
     * 只取该账号名下的意向；旧系统归档数据 buyerId 为 null，自然不会出现。
     */
    Page<Intent> findByBuyerIdOrderBySubmittedAtDescIdDesc(Long buyerId, Pageable pageable);

    /** 买家侧历史总数 */
    long countByBuyerId(Long buyerId);

    /**
     * 买家侧「我的历史」分页（A.2.2），<b>在查询层排除 ARCHIVED</b>（董嘉润 2026-10-21 补，
     * 依据 PR #2 评审意见：归档数据不要查出来再 continue，否则会把它们计入分页 total）。
     * ARCHIVED 为旧系统遗留只读数据（A.2.4-3 B 方案，不挂账号、不复活队列），买家侧不展示。
     */
    Page<Intent> findByBuyerIdAndStatusNotOrderBySubmittedAtDescIdDesc(
            Long buyerId, Intent.Status status, Pageable pageable);

    /**
     * 卖家侧「按商品看意向购买人」（A.2.3-4 两个维度之一）：
     * 某商品下的全部意向，按提交时间正序，含状态。
     */
    List<Intent> findByProductIdOrderBySubmittedAtAscIdAsc(Long productId);

    /**
     * 卖家侧「按客户看购买历史」（A.2.3-4 另一个维度）：
     * 某账号的全部意向，按提交时间倒序。
     */
    List<Intent> findByBuyerIdOrderBySubmittedAtDescIdDesc(Long buyerId);

    /** 某商品当前队列长度（判断是否还有人在排队） */
    long countByProductIdAndStatus(Long productId, Intent.Status status);

    /**
     * 售罄失效（A.1.3-3）：库存归零自动下架时，把该商品队列中所有未成交意向一次性取出，
     * 由调用方批量置为 {@code SOLD_OUT}。补货后这些意向不复活，买家需重新提交。
     */
    List<Intent> findByProductIdAndStatusIn(Long productId, Collection<Intent.Status> statuses);
}
