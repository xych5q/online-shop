package com.group1.onlineshop.repository;

import com.group1.onlineshop.entity.Seller;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface SellerRepository extends JpaRepository<Seller, Long> {

    Optional<Seller> findByUsername(String username);

    Optional<Seller> findByToken(String token);
}
