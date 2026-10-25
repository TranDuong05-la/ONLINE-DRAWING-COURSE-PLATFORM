package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.VoucherClaimJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

/** Claim locking foundation. Settlement currently validates but never transitions a claim. */
public interface VoucherClaimRepository extends Repository<VoucherClaimJpaEntity, Integer> {
    interface LockRoute {
        Integer getId();
        Integer getVoucherId();
        Integer getOrderId();
        Integer getUserId();
    }
    Optional<VoucherClaimJpaEntity> findByOrderIdAndUserId(Integer orderId, Integer userId);
    @Query("select c.id as id, c.voucherId as voucherId, c.orderId as orderId, c.userId as userId " +
            "from VoucherClaimJpaEntity c where c.orderId=:orderId and c.userId=:userId")
    Optional<LockRoute> findLockRouteByOrderIdAndUserId(@Param("orderId") Integer orderId,@Param("userId") Integer userId);
    Optional<VoucherClaimJpaEntity> findByClaimKeyAndUserId(String claimKey, Integer userId);
    Optional<VoucherClaimJpaEntity> findByClaimKey(String claimKey);
    <S extends VoucherClaimJpaEntity> S saveAndFlush(S row);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from VoucherClaimJpaEntity c where c.orderId = :orderId and c.userId = :userId")
    Optional<VoucherClaimJpaEntity> findForUpdate(@Param("orderId") Integer orderId, @Param("userId") Integer userId);
}
