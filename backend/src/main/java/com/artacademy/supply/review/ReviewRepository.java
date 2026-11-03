package com.artacademy.supply.review;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReviewRepository extends JpaRepository<Review, Long> {

    Page<Review> findByProductIdOrderByCreatedAtDescIdDesc(Long productId, Pageable pageable);

    Optional<Review> findByProductIdAndUserId(Long productId, Integer userId);

    @Query("select coalesce(avg(r.rating), 0), count(r) from Review r where r.productId = :productId")
    List<Object[]> stats(@Param("productId") Long productId);

    @Query("select r.rating, count(r) from Review r where r.productId = :productId group by r.rating")
    List<Object[]> histogram(@Param("productId") Long productId);
}
