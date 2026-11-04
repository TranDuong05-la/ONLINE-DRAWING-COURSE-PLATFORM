package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.RefundPersistenceTypes.AmountReservationStatus;
import com.drawingcourse.backend.entity.RefundRequestJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface RefundRequestRepository extends Repository<RefundRequestJpaEntity, Long> {
    Optional<RefundRequestJpaEntity> findById(Long id);
    Optional<RefundRequestJpaEntity> findByUserIdAndIdempotencyKey(Integer userId, String idempotencyKey);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefundRequestJpaEntity r where r.id=:id and r.userId=:userId")
    Optional<RefundRequestJpaEntity> findForUpdate(@Param("id") Long id, @Param("userId") Integer userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefundRequestJpaEntity r where r.id=:id")
    Optional<RefundRequestJpaEntity> findForAdminUpdate(@Param("id") Long id);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select r from RefundRequestJpaEntity r where r.paymentSettlementId=:settlementId and r.amountReservationStatus in :statuses order by r.id")
    List<RefundRequestJpaEntity> findObligationsForUpdate(@Param("settlementId") Integer settlementId,
                                                          @Param("statuses") Collection<AmountReservationStatus> statuses);
    <S extends RefundRequestJpaEntity> S saveAndFlush(S row);

    /** Lịch sử yêu cầu hoàn tiền của học viên (mới nhất trước). */
    List<RefundRequestJpaEntity> findAllByUserIdOrderByRequestedAtDescIdDesc(Integer userId);

    /** Màn admin: mọi yêu cầu, mới nhất trước. */
    List<RefundRequestJpaEntity> findAllByOrderByRequestedAtDescIdDesc();

    /** Yêu cầu của một dòng đơn hàng, để biết đang có yêu cầu chưa kết thúc hay không. */
    List<RefundRequestJpaEntity> findAllByOrderItemIdOrderByIdDesc(Integer orderItemId);
}
