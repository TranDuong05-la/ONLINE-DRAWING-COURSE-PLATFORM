package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.VoucherUserUsageJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface VoucherUserUsageRepository extends Repository<VoucherUserUsageJpaEntity, Integer> {
    Optional<VoucherUserUsageJpaEntity> findByVoucherIdAndUserId(Integer voucherId, Integer userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from VoucherUserUsageJpaEntity u where u.voucherId = :voucherId and u.userId = :userId")
    Optional<VoucherUserUsageJpaEntity> findForUpdate(@Param("voucherId") Integer voucherId, @Param("userId") Integer userId);
    <S extends VoucherUserUsageJpaEntity> S saveAndFlush(S row);
}
