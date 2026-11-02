package com.drawingcourse.backend.service.checkout;

import com.artacademy.enums.Role;
import com.artacademy.security.AuthenticatedUserIdResolver;
import com.artacademy.security.AuthenticatedUserPrincipal;
import com.drawingcourse.backend.entity.CartItemType;
import com.drawingcourse.backend.entity.Order;
import com.drawingcourse.backend.entity.VoucherClaim;
import com.drawingcourse.backend.service.CartState;
import com.drawingcourse.backend.service.StoredOrder;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class CourseOnlyCheckoutApplicationServiceTest {
    @AfterEach void clearSecurityContext() { SecurityContextHolder.clearContext(); }

    @Test void derivesOwnerFromAuthenticatedPrincipalAndDelegatesCourseCheckout() {
        var principal = new AuthenticatedUserPrincipal(73, "buyer@example.test", "unused", Role.USER, true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
        var recordedOwner = new int[] { -1 };
        var capture = new Idempotency();
        var application = application(capture, recordedOwner);

        var result = application.checkout("course-auth-key", request());

        assertEquals(901, result.orderId());
        assertEquals(73, recordedOwner[0]);
        assertEquals(73, capture.userId);
    }

    @Test void rejectsMissingAuthenticatedIdentityBeforeCoordinatorInvocation() {
        SecurityContextHolder.clearContext();
        var capture = new Idempotency();
        var application = application(capture, new int[] { -1 });

        assertThrows(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class,
                () -> application.checkout("key", request()));
        assertNull(capture.userId);
    }

    private static CheckoutRequest request() {
        return new CheckoutRequest(2, List.of(new CheckoutRequest.Selection(6, 1)), Optional.empty(), Optional.empty());
    }

    private static CourseOnlyCheckoutApplicationService application(Idempotency idempotency, int[] recordedOwner) {
        var clock = Clock.fixed(Instant.parse("2026-10-28T05:00:00Z"), ZoneOffset.UTC);
        CheckoutCartPort cart = user -> new CartState(4, user, 2,
                List.of(new CartState.Item(6, CartItemType.COURSE, 91, 1)));
        CourseCatalogPort catalog = id -> Optional.of(new CourseCatalogPort.CourseOffer(id, "Trusted Course", 100_000, true));
        CoursePurchasePort claims = new CoursePurchasePort() {
            public boolean isOwnedBy(Integer user, Integer course) { recordedOwner[0] = user; return false; }
            public boolean hasActivePendingPurchase(Integer user, Integer course, Instant at) { return false; }
            public void claimForOrder(Integer user, Integer course, Integer order, Instant at, Instant expiry) {
                recordedOwner[0] = user;
            }
            public void confirmForOrder(Integer user, Integer course, Integer order, Instant at) { }
            public void releaseForOrder(Integer user, Integer course, Integer order, String reason, Instant at) { }
        };
        VoucherReservationPort vouchers = new VoucherReservationPort() {
            public Optional<Quote> quoteAndLock(Integer user, String code, long subtotal, Instant at) { return Optional.empty(); }
            public void reserveForOrder(Integer user, StoredOrder order, com.drawingcourse.backend.entity.Order snapshot,
                                        Quote quote, Instant at) { }
            public void releaseForOrder(Integer user, Integer order, VoucherClaim.ReleaseReason reason, Instant at) { }
            public void consumeForOrder(Integer user, Integer order, String reference, Instant paidAt, Instant at) { }
        };
        CheckoutOrderPort orders = snapshot -> {
            recordedOwner[0] = snapshot.getUserId();
            return new StoredOrder(901, 0L, snapshot.getExpiresAt(), snapshot.getCreatedAt(),
                    snapshot.getCreatedAt(), snapshot, List.of());
        };
        var dataSource = new JdbcDataSource();
        dataSource.setURL("jdbc:h2:mem:course-only-auth-" + UUID.randomUUID());
        var coordinator = CheckoutCoordinator.courseOnly(idempotency, cart, catalog, claims, vouchers,
                orders, clock, new DataSourceTransactionManager(dataSource));
        return new CourseOnlyCheckoutApplicationService(new AuthenticatedUserIdResolver(), coordinator);
    }

    private static final class Idempotency implements CheckoutIdempotencyPort {
        Integer userId;
        public CheckoutIdempotencyService.ClaimResult claim(Integer userId, String key, String fingerprint) {
            this.userId = userId;
            return new CheckoutIdempotencyService.Claimed(1);
        }
        public void complete(Integer userId, Integer requestId, Integer orderId) { }
    }
}
