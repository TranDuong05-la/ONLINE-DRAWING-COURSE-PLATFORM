package com.drawingcourse.backend.config;

import com.artacademy.security.AuthenticatedUserIdResolver;
import com.artacademy.repository.UserRepository;
import com.drawingcourse.backend.repository.CartRepository;
import com.drawingcourse.backend.repository.CheckoutRequestRepository;
import com.drawingcourse.backend.repository.OrderItemRepository;
import com.drawingcourse.backend.repository.OrderRepository;
import com.drawingcourse.backend.repository.VoucherClaimRepository;
import com.drawingcourse.backend.repository.VoucherRepository;
import com.drawingcourse.backend.repository.VoucherUserUsageRepository;
import com.drawingcourse.backend.service.AuthenticatedOrderCurrentUser;
import com.drawingcourse.backend.service.OrderCurrentUser;
import com.drawingcourse.backend.service.OrderPersistenceService;
import com.drawingcourse.backend.service.checkout.CheckoutCoordinator;
import com.drawingcourse.backend.service.checkout.CheckoutIdempotencyService;
import com.drawingcourse.backend.service.checkout.CourseCatalogPort;
import com.drawingcourse.backend.service.checkout.CourseOnlyCheckoutApplicationService;
import com.drawingcourse.backend.service.checkout.CoursePurchasePort;
import com.drawingcourse.backend.service.checkout.JpaCheckoutCartPort;
import com.drawingcourse.backend.service.checkout.OrderPersistenceCheckoutPort;
import com.drawingcourse.backend.service.checkout.VoucherReservationPort;
import com.drawingcourse.backend.service.checkout.JpaVoucherReservationService;
import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** D-owned composition; no coordinator or API exists unless explicitly enabled and real B/C ports are present. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.checkout.course-only.enabled", havingValue = "true")
public class CourseOnlyCheckoutConfiguration {

    @Bean
    @ConditionalOnBean({VoucherRepository.class, VoucherUserUsageRepository.class,
            VoucherClaimRepository.class, OrderRepository.class})
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(VoucherReservationPort.class)
    JpaVoucherReservationService courseOnlyVoucherReservation(
            VoucherRepository vouchers, VoucherUserUsageRepository userUsage,
            VoucherClaimRepository claims, OrderRepository orders,
            PlatformTransactionManager transactionManager) {
        return new JpaVoucherReservationService(vouchers, userUsage, claims, orders, transactionManager);
    }

    @Bean
    @ConditionalOnBean(AuthenticatedUserIdResolver.class)
    @org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean(OrderCurrentUser.class)
    OrderCurrentUser checkoutOrderCurrentUser(AuthenticatedUserIdResolver userIds) {
        return new AuthenticatedOrderCurrentUser(userIds);
    }

    @Bean
    @ConditionalOnBean({CourseCatalogPort.class, CoursePurchasePort.class, JpaVoucherReservationService.class})
    CheckoutCoordinator courseOnlyCheckoutCoordinator(
            CourseCatalogPort courses, CoursePurchasePort coursePurchases, VoucherReservationPort vouchers,
            CartRepository carts, CheckoutRequestRepository checkoutRequests, UserRepository users,
            OrderRepository orders, OrderItemRepository orderItems, OrderCurrentUser currentUser,
            PlatformTransactionManager transactionManager) {
        var idempotency = new CheckoutIdempotencyService(checkoutRequests, users, orders,
                Clock.systemUTC(), transactionManager);
        var persistence = new OrderPersistenceService(orders, orderItems, currentUser, transactionManager);
        return CheckoutCoordinator.courseOnly(idempotency, new JpaCheckoutCartPort(carts), courses,
                coursePurchases, vouchers, new OrderPersistenceCheckoutPort(persistence),
                Clock.systemUTC(), transactionManager);
    }

    @Bean
    @ConditionalOnBean(CheckoutCoordinator.class)
    CourseOnlyCheckoutApplicationService courseOnlyCheckoutApplicationService(
            AuthenticatedUserIdResolver userIds,
            @org.springframework.beans.factory.annotation.Qualifier("courseOnlyCheckoutCoordinator")
            CheckoutCoordinator coordinator) {
        return new CourseOnlyCheckoutApplicationService(userIds, coordinator);
    }

}
