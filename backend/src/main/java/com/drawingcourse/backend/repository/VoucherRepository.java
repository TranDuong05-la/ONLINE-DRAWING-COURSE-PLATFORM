package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.VoucherJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.Optional;

public interface VoucherRepository extends Repository<VoucherJpaEntity, Integer> {
    Optional<VoucherJpaEntity> findByCode(String code);
    Optional<VoucherJpaEntity> findById(Integer id);
    Page<VoucherJpaEntity> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);
    @Query("select v.id from VoucherJpaEntity v where v.code = :code")
    Optional<Integer> findIdByCode(@Param("code") String code);
    <S extends VoucherJpaEntity> S saveAndFlush(S row);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VoucherJpaEntity v where v.id = :id")
    Optional<VoucherJpaEntity> findByIdForUpdate(@Param("id") Integer id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select v from VoucherJpaEntity v where v.code = :code")
    Optional<VoucherJpaEntity> findByCodeForUpdate(@Param("code") String code);

    /** One page query joins aggregate usage for exactly one authenticated user; no claims are loaded. */
    @Query(value = """
            select v.code as code, v.kind as kind, v.value as value,
                   v.percentageCapVnd as percentageCapVnd, v.active as active,
                   v.validFrom as validFrom, v.validUntil as validUntil,
                   v.minimumSubtotalVnd as minimumSubtotalVnd,
                   v.totalUsageLimit as totalUsageLimit, v.perUserUsageLimit as perUserUsageLimit,
                   v.reservedCount as reservedCount, v.redeemedCount as redeemedCount,
                   coalesce(u.reservedCount, 0) as userReservedCount,
                   coalesce(u.redeemedCount, 0) as userRedeemedCount
              from VoucherJpaEntity v
              left join VoucherUserUsageJpaEntity u
                on u.voucherId = v.id and u.userId = :userId
             order by v.validUntil asc, v.code asc
            """,
            countQuery = "select count(v) from VoucherJpaEntity v")
    Page<VoucherCatalogProjection> findCatalogForUser(@Param("userId") Integer userId, Pageable pageable);
}
