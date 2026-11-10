package com.group1.onlineshop.repository;

import com.group1.onlineshop.entity.Buyer;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

/**
 * 买家账号仓储（挑战需求 A · A.2.1）。
 *
 * <p>用户名唯一性<b>忽略大小写</b>（附三裁定）：数据库唯一索引区分大小写，
 * 故查重与登录均用 {@code lower(username)} 比对，显示时保留用户输入的原样。
 */
public interface BuyerRepository extends JpaRepository<Buyer, Long> {

    /** 查重 / 登录：忽略大小写匹配用户名（A.2.1-3、A.2.1-11） */
    @Query("select b from Buyer b where lower(b.username) = lower(:username)")
    Optional<Buyer> findByUsernameIgnoreCase(@Param("username") String username);

    /** 用户名是否已被占用（注册时校验，忽略大小写） */
    @Query("select count(b) > 0 from Buyer b where lower(b.username) = lower(:username)")
    boolean existsByUsernameIgnoreCase(@Param("username") String username);

    /** 登录态令牌 */
    Optional<Buyer> findByToken(String token);

    /** 卖家侧客户列表：按注册时间倒序分页（A.2.3-1 / A.2.3-6） */
    Page<Buyer> findAllByOrderByCreatedAtDesc(Pageable pageable);
}
