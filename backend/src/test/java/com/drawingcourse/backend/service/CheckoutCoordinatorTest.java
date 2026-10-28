package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.*;
import com.drawingcourse.backend.service.checkout.*;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;

class CheckoutCoordinatorTest {
    private static final Instant NOW = Instant.parse("2026-10-28T05:00:00Z");

    @Test void successfulMixedCheckoutUsesTrustedSnapshotsVoucherAndShipping() {
        Fixture f = new Fixture();
        assertEquals(new CheckoutCoordinator.CheckoutResult(501, false), f.coordinator().checkout(17, "mixed", f.request(true)));
        assertEquals(1, f.orders.insertCount);
        assertEquals(1, f.idempotency.completeCount);
        assertEquals(List.of(11), f.courses.claimed);
        assertThat(f.inventory.calls).containsExactly(new InventoryCall(17, 22, 2, 501, NOW.plusSeconds(1800)));
        assertEquals(1, f.vouchers.reserved);
        assertEquals(200_000L, f.orders.saved.getMerchandiseSubtotalVnd());
        assertEquals(200_000L, f.orders.saved.getEligibleSubtotalVnd());
        assertEquals(10_000L, f.orders.saved.getDiscountVnd());
        assertEquals(15_000L, f.orders.saved.getShippingFeeVnd());
        assertEquals(205_000L, f.orders.saved.getTotalVnd());
        assertThat(f.orders.saved.getItems()).extracting(i -> i.catalogSnapshot().name())
                .containsExactly("Course snapshot", "Product snapshot");
        assertThat(f.orders.saved.getItems()).extracting(OrderItem::allocatedDiscountVnd)
                .containsExactly(5_000L, 5_000L);
    }

    @Test void completedReplayPrecedesCartRevisionAndDoesNotCreateOrder() {
        Fixture f = new Fixture();
        f.idempotency.next = new CheckoutIdempotencyService.Replayed(88);
        assertEquals(new CheckoutCoordinator.CheckoutResult(88, true), f.coordinator().checkout(17, "replay", f.request(false)));
        assertEquals(0, f.cart.locks);
        assertEquals(0, f.orders.insertCount);
    }

    @Test void fingerprintMismatchIsRejectedBeforeCartRead() {
        Fixture f = new Fixture(); f.idempotency.next = new CheckoutIdempotencyService.FingerprintConflict();
        assertReason(CheckoutRejectedException.Reason.IDEMPOTENCY_KEY_REUSED,
                () -> f.coordinator().checkout(17, "reuse", f.request(false)));
        assertEquals(0, f.cart.locks);
    }

    @Test void cartRevisionMismatchStopsBeforeCatalogAndWrites() {
        Fixture f = new Fixture(); f.cart.state = new CartState(4, 17, 4, f.cart.state.items());
        assertReason(CheckoutRejectedException.Reason.CART_REVISION_MISMATCH,
                () -> f.coordinator().checkout(17, "revision", f.request(false)));
        assertEquals(0, f.courseCatalog.lookups + f.productCatalog.lookups + f.orders.insertCount);
    }

    @Test void unavailableItemIsRejected() {
        Fixture f = new Fixture(); f.productCatalog.offer = new ProductCatalogPort.ProductOffer(22, "Product", 50_000, false);
        assertReason(CheckoutRejectedException.Reason.ITEM_UNAVAILABLE,
                () -> f.coordinator().checkout(17, "unavailable", f.request(false)));
        assertEquals(0, f.orders.insertCount);
    }

    @Test void alreadyOwnedAndPendingCourseAreRejected() {
        Fixture owned = new Fixture(); owned.courses.owned = true;
        assertReason(CheckoutRejectedException.Reason.COURSE_ALREADY_OWNED,
                () -> owned.coordinator().checkout(17, "owned", owned.request(false)));
        Fixture pending = new Fixture(); pending.courses.pending = true;
        assertReason(CheckoutRejectedException.Reason.COURSE_PENDING_PURCHASE,
                () -> pending.coordinator().checkout(17, "pending", pending.request(false)));
    }

    @Test void insufficientStockPropagatesAndDoesNotCompleteIdempotency() {
        Fixture f = new Fixture();
        f.inventory.failure = new CheckoutRejectedException(CheckoutRejectedException.Reason.INSUFFICIENT_STOCK, "short");
        assertReason(CheckoutRejectedException.Reason.INSUFFICIENT_STOCK,
                () -> f.coordinator().checkout(17, "stock", f.request(false)));
        assertEquals(1, f.orders.insertCount); // Fakes cannot establish rollback; see the H2 transaction test.
        assertEquals(0, f.idempotency.completeCount);
    }

    @Test void voucherRejectionPreventsOrderCreation() {
        Fixture f = new Fixture(); f.vouchers.fail = true;
        assertReason(CheckoutRejectedException.Reason.VOUCHER_REJECTED,
                () -> f.coordinator().checkout(17, "voucher", f.request(true)));
        assertEquals(0, f.orders.insertCount);
    }

    @Test void completedRetryDoesNotCreateDuplicateOrder() {
        Fixture f = new Fixture(); f.idempotency.becomeReplay = true;
        assertFalse(f.coordinator().checkout(17, "retry", f.request(false)).replayed());
        assertEquals(new CheckoutCoordinator.CheckoutResult(501, true), f.coordinator().checkout(17, "retry", f.request(false)));
        assertEquals(1, f.orders.insertCount);
    }

    @Test void selectionQuantityMustMatchLockedCart() {
        Fixture f = new Fixture();
        var request = new CheckoutRequest(3, List.of(new CheckoutRequest.Selection(2, 1)), Optional.empty(), Optional.empty());
        assertReason(CheckoutRejectedException.Reason.CART_SELECTION_MISMATCH,
                () -> f.coordinator().checkout(17, "quantity", request));
        assertEquals(0, f.orders.insertCount);
    }

    private static void assertReason(CheckoutRejectedException.Reason expected, Runnable action) {
        assertEquals(expected, assertThrows(CheckoutRejectedException.class, action::run).reason());
    }
    private record InventoryCall(Integer userId, Integer productId, int quantity, Integer orderId, Instant expiresAt) { }

    private static final class Fixture {
        final Idempotency idempotency = new Idempotency();
        final Cart cart = new Cart();
        final Courses courseCatalog = new Courses();
        final Products productCatalog = new Products();
        final CoursePurchases courses = new CoursePurchases();
        final Inventory inventory = new Inventory();
        final Vouchers vouchers = new Vouchers();
        final Orders orders = new Orders();
        final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        Fixture() { vouchers.quote = new VoucherReservationPort.Quote(9, 1,
                new OrderVoucher("SAVE10", OrderVoucher.Kind.FIXED_VND, 10_000, 10_000)); }
        CheckoutRequest request(boolean voucher) {
            return new CheckoutRequest(3, List.of(new CheckoutRequest.Selection(2, 2), new CheckoutRequest.Selection(1, 1)),
                    voucher ? Optional.of(" SAVE10 ") : Optional.empty(), Optional.empty());
        }
        CheckoutCoordinator coordinator() {
            JdbcDataSource ds = new JdbcDataSource(); ds.setURL("jdbc:h2:mem:coordinator-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            return new CheckoutCoordinator(idempotency, cart, courseCatalog, productCatalog, courses, inventory,
                    vouchers, (user, lines, address) -> 15_000, orders, clock, new DataSourceTransactionManager(ds));
        }
    }
    private static final class Idempotency implements CheckoutIdempotencyPort {
        CheckoutIdempotencyService.ClaimResult next = new CheckoutIdempotencyService.Claimed(41);
        boolean becomeReplay; int completeCount;
        public CheckoutIdempotencyService.ClaimResult claim(Integer u, String k, String f) { return next; }
        public void complete(Integer u, Integer r, Integer o) { completeCount++; if (becomeReplay) next = new CheckoutIdempotencyService.Replayed(o); }
    }
    private static final class Cart implements CheckoutCartPort {
        CartState state = new CartState(4, 17, 3, List.of(new CartState.Item(1, CartItemType.COURSE, 11, 1),
                new CartState.Item(2, CartItemType.PRODUCT, 22, 2)));
        int locks;
        public CartState lockForCheckout(Integer user) { locks++; return state; }
    }
    private static final class Courses implements CourseCatalogPort {
        int lookups; boolean owned, pending;
        public Optional<CourseOffer> findForCheckout(Integer id) { lookups++; return Optional.of(new CourseOffer(id, "Course snapshot", 100_000, true)); }
    }
    private static final class Products implements ProductCatalogPort {
        int lookups; ProductOffer offer = new ProductOffer(22, "Product snapshot", 50_000, true);
        public Optional<ProductOffer> findForCheckout(Integer id) { lookups++; return Optional.of(offer); }
    }
    private static final class CoursePurchases implements CoursePurchasePort {
        boolean owned, pending; final List<Integer> claimed = new ArrayList<>();
        public boolean isOwnedBy(Integer u, Integer c) { return owned; }
        public boolean hasActivePendingPurchase(Integer u, Integer c, Instant at) { return pending; }
        public void claimForOrder(Integer u, Integer c, Integer o, Instant at, Instant expiry) { claimed.add(c); }
        public void confirmForOrder(Integer u, Integer c, Integer o, Instant at) { }
        public void releaseForOrder(Integer u, Integer c, Integer o, String reason, Instant at) { }
    }
    private static final class Inventory implements InventoryReservationPort {
        final List<InventoryCall> calls = new ArrayList<>(); RuntimeException failure;
        public void reserveForOrder(Integer u, Integer p, int q, Integer o, Instant expiry) {
            calls.add(new InventoryCall(u, p, q, o, expiry)); if (failure != null) throw failure;
        }
        public void confirmForOrder(Integer u, Integer p, Integer o, Instant at) { }
        public void releaseForOrder(Integer u, Integer p, Integer o, String reason, Instant at) { }
    }
    private static final class Vouchers implements VoucherReservationPort {
        Quote quote; boolean fail; int reserved;
        public Optional<Quote> quoteAndLock(Integer u, String code, long eligible, Instant at) {
            if (fail) throw new CheckoutRejectedException(CheckoutRejectedException.Reason.VOUCHER_REJECTED, "rejected");
            return Optional.ofNullable(quote);
        }
        public void reserveForOrder(Integer u, StoredOrder s, Order o, Quote q, Instant at) { reserved++; }
        public void releaseForOrder(Integer u, Integer o, VoucherClaim.ReleaseReason reason, Instant at) { }
        public void consumeForOrder(Integer u, Integer o, String ref, Instant paidAt, Instant at) { }
    }
    private static final class Orders implements CheckoutOrderPort {
        int insertCount; Order saved;
        public StoredOrder insertNew(Order snapshot) {
            insertCount++; saved = snapshot;
            return new StoredOrder(500 + insertCount, 0L, snapshot.getExpiresAt(), snapshot.getCreatedAt(), snapshot.getCreatedAt(), snapshot, List.of());
        }
    }
}
