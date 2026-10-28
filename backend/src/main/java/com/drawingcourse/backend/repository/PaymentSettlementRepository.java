package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.PaymentSettlementJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

/** Read/insert persistence contract; settlements intentionally expose no update or delete operation. */
public interface PaymentSettlementRepository extends Repository<PaymentSettlementJpaEntity, Integer> {
    Optional<PaymentSettlementJpaEntity> findById(Integer id);
    Optional<PaymentSettlementJpaEntity> findByOrderId(Integer orderId);
    /** Current locking read; avoids a stale REPEATABLE_READ snapshot after waiting on the Order lock. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from PaymentSettlementJpaEntity s where s.orderId=:orderId")
    Optional<PaymentSettlementJpaEntity> findForUpdateByOrderId(@Param("orderId") Integer orderId);
    Optional<PaymentSettlementJpaEntity> findByPaymentAttemptId(Integer paymentAttemptId);
    Optional<PaymentSettlementJpaEntity> findByPaymentEventId(Integer paymentEventId);
    <S extends PaymentSettlementJpaEntity> S saveAndFlush(S settlement);
}
