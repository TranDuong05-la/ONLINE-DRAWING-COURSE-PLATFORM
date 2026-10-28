package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.VoucherJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;

public interface VoucherRepository extends Repository<VoucherJpaEntity, Integer> {
    Optional<VoucherJpaEntity> findByCode(String code);
    @Query("select v.id from VoucherJpaEntity v where v.code = :code")
    Optional<Integer> findIdByCode(@Param("code") String code);
    <S extends VoucherJpaEntity> S saveAndFlush(S row);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VoucherJpaEntity v where v.id = :id")
    Optional<VoucherJpaEntity> findByIdForUpdate(@Param("id") Integer id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VoucherJpaEntity v where v.code = :code")
    Optional<VoucherJpaEntity> findByCodeForUpdate(@Param("code") String code);
}
