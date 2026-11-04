package com.drawingcourse.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.drawingcourse.backend.entity.CartItemType;
import com.drawingcourse.backend.entity.Order;
import com.drawingcourse.backend.entity.OrderItemType;
import com.drawingcourse.backend.entity.OrderVoucher;
import com.drawingcourse.backend.entity.VoucherClaim;
import com.drawingcourse.backend.service.checkout.CheckoutCartPort;
import com.drawingcourse.backend.service.checkout.CheckoutCoordinator;
import com.drawingcourse.backend.service.checkout.CheckoutIdempotencyPort;
import com.drawingcourse.backend.service.checkout.CheckoutIdempotencyService;
import com.drawingcourse.backend.service.checkout.CheckoutOrderPort;
import com.drawingcourse.backend.service.checkout.CheckoutRejectedException;
import com.drawingcourse.backend.service.checkout.CheckoutRequest;
import com.drawingcourse.backend.service.checkout.ComboCatalogPort;
import com.drawingcourse.backend.service.checkout.ComboPurchasePort;
import com.drawingcourse.backend.service.checkout.CourseCatalogPort;
import com.drawingcourse.backend.service.checkout.CoursePurchasePort;
import com.drawingcourse.backend.service.checkout.VoucherReservationPort;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.h2.jdbcx.JdbcDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** Combo lines in Checkout: one Order line per Combo, one claim per Course, all-or-nothing, never a Product. */
class CheckoutCoordinatorComboTest {
    private static final Instant NOW = Instant.parse("2026-11-04T05:00:00Z");

    @Test
    void comboIsOneNonVoucherLine_andReservesEveryComponentCourseInPositionOrder() {
        Fixture f = new Fixture();
        f.cart.items = List.of(new CartState.Item(1, CartItemType.COMBO, 7, 1));

        var result = f.coordinator().checkoutCoursesOnly(17, "combo", f.request(1));

        assertEquals(new CheckoutCoordinator.CheckoutResult(501, false), result);
        assertEquals(1, f.orders.insertCount);
        assertEquals(1, f.idempotency.completeCount);
        var line = f.orders.saved.getItems().getFirst();
        assertEquals(1, f.orders.saved.getItems().size());
        assertEquals(OrderItemType.COMBO, line.catalogSnapshot().itemType());
        assertEquals(7, line.catalogSnapshot().itemId());
        assertEquals(1, line.catalogSnapshot().quantity());
        assertEquals(250_000L, line.netAmountVnd());
        assertEquals(false, line.catalogSnapshot().voucherEligible());
        assertEquals(250_000L, f.orders.saved.getTotalVnd());
        // chỉ combo mới giữ chỗ khóa, không có claim khóa lẻ
        assertThat(f.standalone.claimed).isEmpty();
        assertEquals(1, f.combos.claims.size());
        var claim = f.combos.claims.getFirst();
        assertEquals(1000, claim.orderItemId());
        assertEquals(501, claim.orderId());
        assertThat(claim.components()).extracting(ComboCatalogPort.Component::courseId).containsExactly(11, 12);
        assertThat(claim.components()).extracting(ComboCatalogPort.Component::position).containsExactly(1, 2);
        assertEquals(NOW.plusSeconds(1800), claim.expiresAt());
    }

    @Test
    void comboAlongsideAnUnrelatedCourse_isAllowed() {
        Fixture f = new Fixture();
        f.cart.items = List.of(new CartState.Item(1, CartItemType.COMBO, 7, 1),
                new CartState.Item(2, CartItemType.COURSE, 99, 1));

        f.coordinator().checkoutCoursesOnly(17, "mix", f.request(1, 2));

        assertEquals(2, f.orders.saved.getItems().size());
        assertEquals(List.of(99), f.standalone.claimed);
        assertEquals(1, f.combos.claims.size());
    }

    @Test
    void courseAlreadyInsideTheCombo_isRejectedAsOverlapBeforeAnyWrite() {
        Fixture f = new Fixture();
        f.cart.items = List.of(new CartState.Item(1, CartItemType.COMBO, 7, 1),
                new CartState.Item(2, CartItemType.COURSE, 12, 1));

        assertReason(CheckoutRejectedException.Reason.COMBO_COURSE_OVERLAP,
                () -> f.coordinator().checkoutCoursesOnly(17, "overlap", f.request(1, 2)));

        assertNothingWritten(f);
    }

    @Test
    void ownedOrPendingCourseInsideTheCombo_isRejected() {
        Fixture owned = new Fixture();
        owned.cart.items = List.of(new CartState.Item(1, CartItemType.COMBO, 7, 1));
        owned.standalone.owned.add(12);
        assertReason(CheckoutRejectedException.Reason.COURSE_ALREADY_OWNED,
                () -> owned.coordinator().checkoutCoursesOnly(17, "owned", owned.request(1)));
        assertNothingWritten(owned);

        Fixture pending = new Fixture();
        pending.cart.items = List.of(new CartState.Item(1, CartItemType.COMBO, 7, 1));
        pending.standalone.pending.add(11);
        assertReason(CheckoutRejectedException.Reason.COURSE_PENDING_PURCHASE,
                () -> pending.coordinator().checkoutCoursesOnly(17, "pending", pending.request(1)));
        assertNothingWritten(pending);
    }

    @Test
    void unavailableComboIsRejected_andAnUnpurchasableOfferToo() {
        Fixture missing = new Fixture();
        missing.cart.items = List.of(new CartState.Item(1, CartItemType.COMBO, 7, 1));
        missing.catalog.offer = null;
        assertReason(CheckoutRejectedException.Reason.ITEM_UNAVAILABLE,
                () -> missing.coordinator().checkoutCoursesOnly(17, "missing", missing.request(1)));
        assertNothingWritten(missing);

        Fixture notPurchasable = new Fixture();
        notPurchasable.cart.items = List.of(new CartState.Item(1, CartItemType.COMBO, 7, 1));
        notPurchasable.catalog.offer = new ComboCatalogPort.ComboOffer(7, "Combo", 250_000, 1,
                List.of(new ComboCatalogPort.Component(11, 1), new ComboCatalogPort.Component(12, 2)), false);
        assertReason(CheckoutRejectedException.Reason.ITEM_UNAVAILABLE,
                () -> notPurchasable.coordinator().checkoutCoursesOnly(17, "np", notPurchasable.request(1)));
    }

    @Test
    void comboClaimFailureLeavesNoSuccessMarker() {
        Fixture f = new Fixture();
        f.cart.items = List.of(new CartState.Item(1, CartItemType.COMBO, 7, 1));
        f.combos.failure = new IllegalStateException("claim failed");

        assertThrows(IllegalStateException.class, () -> f.coordinator().checkoutCoursesOnly(17, "fail", f.request(1)));

        assertEquals(0, f.idempotency.completeCount);
    }

    @Test
    void comboIsRejectedWhenTheCoordinatorHasNoComboParticipants() {
        Fixture f = new Fixture();
        f.cart.items = List.of(new CartState.Item(1, CartItemType.COMBO, 7, 1));
        var courseOnly = CheckoutCoordinator.courseOnly(f.idempotency, f.cart, f.courseCatalog, f.standalone,
                f.vouchers, f.orders, f.clock, f.tx());

        assertReason(CheckoutRejectedException.Reason.UNSUPPORTED_ITEM_TYPE,
                () -> courseOnly.checkoutCoursesOnly(17, "no-combo", f.request(1)));
        assertEquals(0, f.orders.insertCount);
        assertEquals(0, f.catalog.lookups);
    }

    @Test
    void comboIdIsNeverTreatedAsAProductInTheGeneralCoordinator() {
        Fixture f = new Fixture();
        f.cart.items = List.of(new CartState.Item(1, CartItemType.COMBO, 7, 1));
        boolean[] productLookup = {false};
        var general = new CheckoutCoordinator(f.idempotency, f.cart, f.courseCatalog,
                id -> { productLookup[0] = true; return Optional.empty(); }, f.standalone,
                new NoInventory(), f.vouchers, (user, lines, address) -> 0, f.orders, f.clock, f.tx());

        assertReason(CheckoutRejectedException.Reason.UNSUPPORTED_ITEM_TYPE,
                () -> general.checkout(17, "general", f.request(1)));
        assertTrue(!productLookup[0], "a Combo id must never be looked up as a Product");
        assertEquals(0, f.orders.insertCount);
    }

    @Test
    void comboVoucherIsRejectedBecauseComboCarriesNoEligibleAmount() {
        Fixture f = new Fixture();
        f.cart.items = List.of(new CartState.Item(1, CartItemType.COMBO, 7, 1));
        f.vouchers.quote = null; // không có voucher áp dụng được cho số tiền đủ điều kiện = 0

        var request = new CheckoutRequest(3, List.of(new CheckoutRequest.Selection(1, 1)),
                Optional.of("SAVE10"), Optional.empty());
        assertReason(CheckoutRejectedException.Reason.VOUCHER_REJECTED,
                () -> f.coordinator().checkoutCoursesOnly(17, "voucher", request));

        assertEquals(0, f.orders.insertCount);
        assertEquals(0L, f.vouchers.lastEligibleAmount);
    }

    @Test
    void replayDoesNotClaimAgain() {
        Fixture f = new Fixture();
        f.cart.items = List.of(new CartState.Item(1, CartItemType.COMBO, 7, 1));
        var coordinator = f.coordinator();
        coordinator.checkoutCoursesOnly(17, "replay", f.request(1));
        f.idempotency.next = new CheckoutIdempotencyService.Replayed(501);

        assertEquals(new CheckoutCoordinator.CheckoutResult(501, true),
                coordinator.checkoutCoursesOnly(17, "replay", f.request(1)));

        assertEquals(1, f.combos.claims.size());
        assertEquals(1, f.orders.insertCount);
    }

    // ------------------------------------------------------------------ helpers

    private static void assertNothingWritten(Fixture f) {
        assertEquals(0, f.orders.insertCount);
        assertEquals(0, f.idempotency.completeCount);
        assertThat(f.standalone.claimed).isEmpty();
        assertThat(f.combos.claims).isEmpty();
    }

    private static void assertReason(CheckoutRejectedException.Reason expected, Runnable action) {
        assertEquals(expected, assertThrows(CheckoutRejectedException.class, action::run).reason());
    }

    private record ComboClaim(Integer orderId, Integer orderItemId, List<ComboCatalogPort.Component> components,
                              Instant expiresAt) { }

    private static final class Fixture {
        final Idempotency idempotency = new Idempotency();
        final CartPort cart = new CartPort();
        final Courses courseCatalog = new Courses();
        final StandalonePurchases standalone = new StandalonePurchases();
        final ComboCatalog catalog = new ComboCatalog();
        final ComboPurchases combos = new ComboPurchases();
        final Vouchers vouchers = new Vouchers();
        final Orders orders = new Orders();
        final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);

        CheckoutRequest request(Integer... cartItemIds) {
            var selections = new ArrayList<CheckoutRequest.Selection>();
            for (Integer id : cartItemIds) selections.add(new CheckoutRequest.Selection(id, 1));
            return new CheckoutRequest(3, selections, Optional.empty(), Optional.empty());
        }

        CheckoutCoordinator coordinator() {
            return CheckoutCoordinator.courseOnlyWithCombos(idempotency, cart, courseCatalog, standalone, catalog,
                    combos, vouchers, orders, clock, tx());
        }

        PlatformTransactionManager tx() {
            JdbcDataSource ds = new JdbcDataSource();
            ds.setURL("jdbc:h2:mem:coordinator-combo-" + UUID.randomUUID() + ";DB_CLOSE_DELAY=-1");
            return new DataSourceTransactionManager(ds);
        }
    }

    private static final class Idempotency implements CheckoutIdempotencyPort {
        CheckoutIdempotencyService.ClaimResult next = new CheckoutIdempotencyService.Claimed(41);
        int completeCount;
        public CheckoutIdempotencyService.ClaimResult claim(Integer u, String k, String f) { return next; }
        public void complete(Integer u, Integer r, Integer o) { completeCount++; }
    }

    private static final class CartPort implements CheckoutCartPort {
        List<CartState.Item> items = List.of();
        public CartState lockForCheckout(Integer user) { return new CartState(4, 17, 3, items); }
    }

    private static final class Courses implements CourseCatalogPort {
        public Optional<CourseOffer> findForCheckout(Integer id) {
            return Optional.of(new CourseOffer(id, "Course " + id, 100_000, true));
        }
    }

    private static final class ComboCatalog implements ComboCatalogPort {
        int lookups;
        ComboOffer offer = new ComboOffer(7, "Combo snapshot", 250_000, 3,
                List.of(new Component(11, 1), new Component(12, 2)), true);
        public Optional<ComboOffer> findForCheckout(Integer id) { lookups++; return Optional.ofNullable(offer); }
    }

    private static final class StandalonePurchases implements CoursePurchasePort {
        final Set<Integer> owned = new HashSet<>();
        final Set<Integer> pending = new HashSet<>();
        final List<Integer> claimed = new ArrayList<>();
        public boolean isOwnedBy(Integer u, Integer c) { return owned.contains(c); }
        public boolean hasActivePendingPurchase(Integer u, Integer c, Instant at) { return pending.contains(c); }
        public void claimForOrder(Integer u, Integer c, Integer o, Integer item, Instant at, Instant expiry) {
            claimed.add(c);
        }
        public void confirmForOrder(Integer u, Integer c, Integer o, Instant at) { }
        public void releaseForOrder(Integer u, Integer c, Integer o, String reason, Instant at) { }
    }

    private static final class ComboPurchases implements ComboPurchasePort {
        final List<ComboClaim> claims = new ArrayList<>();
        RuntimeException failure;
        public void claimComboForOrder(Integer user, Integer orderId, Integer orderItemId,
                                       List<ComboCatalogPort.Component> components, Instant at, Instant expiry) {
            claims.add(new ComboClaim(orderId, orderItemId, components, expiry));
            if (failure != null) throw failure;
        }
    }

    private static final class Vouchers implements VoucherReservationPort {
        Quote quote; long lastEligibleAmount = -1;
        public Optional<Quote> quoteAndLock(Integer u, String code, long eligible, Instant at) {
            lastEligibleAmount = eligible;
            return Optional.ofNullable(quote);
        }
        public void reserveForOrder(Integer u, StoredOrder s, Order o, Quote q, Instant at) { }
        public void releaseForOrder(Integer u, Integer o, VoucherClaim.ReleaseReason reason, Instant at) { }
        public void consumeForOrder(Integer u, Integer o, String ref, Instant paidAt, Instant receivedAt,
                                    Instant settledAt) { }
    }

    private static final class NoInventory implements com.drawingcourse.backend.service.checkout.InventoryReservationPort {
        public void reserveForOrder(Integer u, Integer p, int q, Integer o, Instant expiry) { }
        public void confirmForOrder(Integer u, Integer p, Integer o, Instant at) { }
        public void releaseForOrder(Integer u, Integer p, Integer o, String reason, Instant at) { }
    }

    private static final class Orders implements CheckoutOrderPort {
        int insertCount; Order saved;
        public StoredOrder insertNew(Order snapshot) {
            insertCount++;
            saved = snapshot;
            var identities = new ArrayList<StoredOrder.ItemIdentity>();
            for (int i = 0; i < snapshot.getItems().size(); i++) {
                var item = snapshot.getItems().get(i);
                identities.add(new StoredOrder.ItemIdentity(1000 + i, i, item.catalogSnapshot().itemType(),
                        item.catalogSnapshot().itemId()));
            }
            return new StoredOrder(500 + insertCount, 0L, snapshot.getExpiresAt(), snapshot.getCreatedAt(),
                    snapshot.getCreatedAt(), snapshot, identities);
        }
    }
}
