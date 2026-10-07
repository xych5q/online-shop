package com.group1.onlineshop.repository;

import com.group1.onlineshop.entity.Intent;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.Optional;

public interface IntentRepository extends JpaRepository<Intent, Long> {

    /** 队列：某商品下排队中的意向，按提交时间正序（id 兜底防同一时刻） */
    List<Intent> findByProductIdAndStatusOrderBySubmittedAtAscIdAsc(Long productId, Intent.Status status);

    /** 当前交易对象 */
    Optional<Intent> findByProductIdAndStatus(Long productId, Intent.Status status);

    /** 待卖家处置的失败意向 */
    List<Intent> findByProductIdAndStatusAndDisposedFalseOrderBySubmittedAtAsc(Long productId, Intent.Status status);

    Optional<Intent> findByCode(String code);

    /** 某商品下的全部意向（历史流水） */
    List<Intent> findByProductIdOrderBySubmittedAtAsc(Long productId);
}
