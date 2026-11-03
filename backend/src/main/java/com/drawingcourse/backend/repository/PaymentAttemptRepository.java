package com.drawingcourse.backend.repository;

import com.drawingcourse.backend.entity.PaymentAttemptJpaEntity;
import jakarta.persistence.LockModeType;
import org.springframework.data.repository.Repository;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;
import java.util.Optional;
import java.util.List;

public interface PaymentAttemptRepository extends Repository<PaymentAttemptJpaEntity,Integer> {
    interface PaymentAttemptRoute {
        Integer getId();
        Integer getOrderId();
        Integer getUserId();
        String getProvider();
        String getMerchantReference();
    }
    @Query("select p.id as id, p.orderId as orderId, p.userId as userId, p.provider as provider, " +
            "p.merchantReference as merchantReference from PaymentAttemptJpaEntity p " +
            "where p.provider=:provider and p.merchantReference=:merchantReference")
    Optional<PaymentAttemptRoute> findRouteByProviderAndMerchantReference(
            @Param("provider") String provider, @Param("merchantReference") String merchantReference);
    Optional<PaymentAttemptJpaEntity> findByIdAndUserId(Integer id,Integer userId);
    Optional<PaymentAttemptJpaEntity> findByProviderAndMerchantReferenceAndUserId(String provider,String merchantReference,Integer userId);
    Optional<PaymentAttemptJpaEntity> findByProviderAndMerchantReference(String provider,String merchantReference);
    Optional<PaymentAttemptJpaEntity> findByProviderAndTransactionReference(String provider,String transactionReference);
    List<PaymentAttemptJpaEntity> findAllByOrderIdAndUserId(Integer orderId,Integer userId);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentAttemptJpaEntity p where p.orderId=:orderId and p.userId=:userId order by p.id")
    List<PaymentAttemptJpaEntity> findAllForOrderUpdate(@Param("orderId") Integer orderId,@Param("userId") Integer userId);
    <S extends PaymentAttemptJpaEntity> S saveAndFlush(S row);
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from PaymentAttemptJpaEntity p where p.id=:id and p.userId=:userId")
    Optional<PaymentAttemptJpaEntity> findForUpdate(@Param("id") Integer id,@Param("userId") Integer userId);
}
