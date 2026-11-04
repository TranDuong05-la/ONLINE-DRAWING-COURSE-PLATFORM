package com.drawingcourse.backend.config;

import com.artacademy.repository.UserRepository;
import com.artacademy.security.AuthenticatedUserIdResolver;
import com.drawingcourse.backend.repository.*;
import com.drawingcourse.backend.service.OrderCurrentUser;
import com.drawingcourse.backend.service.OrderPersistenceService;
import com.drawingcourse.backend.service.AuthenticatedOrderCurrentUser;
import com.drawingcourse.backend.service.checkout.*;
import java.time.Clock;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

/** Product Checkout is opt-in and only composes real B/C/D/E participants. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.checkout.product.enabled", havingValue = "true")
@ConditionalOnBean({CourseCatalogPort.class, CoursePurchasePort.class, ProductCatalogPort.class,
        InventoryReservationPort.class, ShippingQuotePort.class})
public class ProductCheckoutConfiguration {
    @Bean @ConditionalOnMissingBean(OrderCurrentUser.class)
    OrderCurrentUser productCheckoutOrderCurrentUser(AuthenticatedUserIdResolver resolver) {
        return new AuthenticatedOrderCurrentUser(resolver);
    }
    @Bean @ConditionalOnMissingBean(VoucherReservationPort.class)
    JpaVoucherReservationService productCheckoutVoucherReservation(VoucherRepository vouchers,
            VoucherUserUsageRepository usage, VoucherClaimRepository claims, OrderRepository orders,
            PlatformTransactionManager transactions) {
        return new JpaVoucherReservationService(vouchers, usage, claims, orders, transactions);
    }
    @Bean("productCheckoutCoordinator")
    CheckoutCoordinator productCheckoutCoordinator(CourseCatalogPort courses, ProductCatalogPort products,
            CoursePurchasePort coursePurchases, InventoryReservationPort inventory, ShippingQuotePort shipping,
            VoucherReservationPort vouchers, CartRepository carts, CheckoutRequestRepository checkoutRequests,
            UserRepository users, OrderRepository orders, OrderItemRepository orderItems, OrderCurrentUser currentUser,
            OrderShippingAddressRepository shippingAddresses, PlatformTransactionManager transactions,
            ObjectProvider<ComboCatalogPort> comboCatalog, ObjectProvider<ComboPurchasePort> comboPurchases) {
        var idempotency = new CheckoutIdempotencyService(checkoutRequests, users, orders, Clock.systemUTC(), transactions);
        var persistence = new OrderPersistenceService(orders, orderItems, currentUser, transactions, shippingAddresses);
        // A Combo can share an Order with Products only when B's Combo catalog and C's claim participant are present.
        ComboCatalogPort combos = comboCatalog.getIfAvailable();
        ComboPurchasePort comboClaims = comboPurchases.getIfAvailable();
        if (combos != null && comboClaims != null) {
            return new CheckoutCoordinator(idempotency, new JpaCheckoutCartPort(carts), courses, products,
                    coursePurchases, inventory, vouchers, shipping,
                    new OrderPersistenceCheckoutPort(persistence), Clock.systemUTC(), transactions,
                    combos, comboClaims);
        }
        return new CheckoutCoordinator(idempotency, new JpaCheckoutCartPort(carts), courses, products,
                coursePurchases, inventory, vouchers, shipping,
                new OrderPersistenceCheckoutPort(persistence), Clock.systemUTC(), transactions);
    }
    @Bean ProductCheckoutApplicationService productCheckoutApplicationService(AuthenticatedUserIdResolver resolver,
            @Qualifier("productCheckoutCoordinator") CheckoutCoordinator coordinator) {
        return new ProductCheckoutApplicationService(resolver, coordinator);
    }
}
