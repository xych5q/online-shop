package com.group1.onlineshop.repository;

import com.group1.onlineshop.entity.StockLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

/** 库存流水仓储：按商品倒序查看调整历史（最新在前）。 */
public interface StockLogRepository extends JpaRepository<StockLog, Long> {

    List<StockLog> findByProductIdOrderByIdDesc(Long productId);
}
