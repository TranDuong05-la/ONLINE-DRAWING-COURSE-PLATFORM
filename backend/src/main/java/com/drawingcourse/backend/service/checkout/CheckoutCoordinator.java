package com.drawingcourse.backend.service.checkout;

import com.drawingcourse.backend.entity.CartItemType;
import com.drawingcourse.backend.entity.Order;
import com.drawingcourse.backend.entity.OrderItemType;
import com.drawingcourse.backend.entity.OrderLineSnapshot;
import com.drawingcourse.backend.service.ActivePendingCoursePurchaseProvider;
import com.drawingcourse.backend.service.CartState;
import com.drawingcourse.backend.service.StoredOrder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;

/**
 * Unwired Checkout orchestration core. All persistence/reservation ports must join the same local transaction.
 * No HTTP endpoint, gateway call, scheduled work, or production B/C/E adapter is registered by this class.
 */
public final class CheckoutCoordinator {
    private final CheckoutIdempotencyPort idempotency;
    private final CheckoutCartPort carts;
    private final CourseCatalogPort courses;
    private final ProductCatalogPort products;
    private final CoursePurchasePort coursePurchases;
    private final InventoryReservationPort inventory;
    private final VoucherReservationPort vouchers;
    private final ShippingQuotePort shipping;
    private final CheckoutOrderPort orders;
    private final Clock clock;
    private final TransactionTemplate transaction;

    public CheckoutCoordinator(CheckoutIdempotencyPort idempotency, CheckoutCartPort carts,
            CourseCatalogPort courses, ProductCatalogPort products, CoursePurchasePort coursePurchases,
            InventoryReservationPort inventory, VoucherReservationPort vouchers, ShippingQuotePort shipping,
            CheckoutOrderPort orders, Clock clock, PlatformTransactionManager transactionManager) {
        this.idempotency = Objects.requireNonNull(idempotency);
        this.carts = Objects.requireNonNull(carts);
        this.courses = Objects.requireNonNull(courses);
        this.products = Objects.requireNonNull(products);
        this.coursePurchases = Objects.requireNonNull(coursePurchases);
        this.inventory = Objects.requireNonNull(inventory);
        this.vouchers = Objects.requireNonNull(vouchers);
        this.shipping = Objects.requireNonNull(shipping);
        this.orders = Objects.requireNonNull(orders);
        this.clock = Objects.requireNonNull(clock);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /** authenticatedUserId must be resolved from the verified principal at the application boundary. */
    public CheckoutResult checkout(Integer authenticatedUserId, String idempotencyKey, CheckoutRequest request) {
        if (authenticatedUserId == null || authenticatedUserId <= 0)
            throw new IllegalArgumentException("Authenticated user ID must be positive");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 64)
            throw rejected(CheckoutRejectedException.Reason.INVALID_IDEMPOTENCY_KEY, "Idempotency-Key must contain 1 to 64 characters");
        Objects.requireNonNull(request, "request");
        return transaction.execute(status -> checkoutInTransaction(authenticatedUserId, idempotencyKey, request));
    }

    private CheckoutResult checkoutInTransaction(Integer userId, String key, CheckoutRequest request) {
        String fingerprint = request.canonicalFingerprint();
        CheckoutIdempotencyService.ClaimResult claim;
        try {
            claim = idempotency.claim(userId, key, fingerprint);
        } catch (CheckoutIdempotencyService.ConcurrentClaimException race) {
            throw new CheckoutRejectedException(CheckoutRejectedException.Reason.IDEMPOTENCY_RACE,
                    "Checkout request is being claimed concurrently; retry", race);
        }
        if (claim instanceof CheckoutIdempotencyService.Replayed replay)
            return new CheckoutResult(replay.orderId(), true);
        if (claim instanceof CheckoutIdempotencyService.FingerprintConflict)
            throw rejected(CheckoutRejectedException.Reason.IDEMPOTENCY_KEY_REUSED,
                    "Idempotency-Key was already used with different Checkout content");
        if (claim instanceof CheckoutIdempotencyService.InProgress)
            throw rejected(CheckoutRejectedException.Reason.IDEMPOTENCY_IN_PROGRESS,
                    "Checkout request is already processing; retry later");
        Integer requestId = ((CheckoutIdempotencyService.Claimed) claim).requestId();

        CartState cart = carts.lockForCheckout(userId);
        if (cart == null || cart.cartId() == null)
            throw rejected(CheckoutRejectedException.Reason.CART_NOT_FOUND, "Cart not found");
        if (!userId.equals(cart.userId())) throw new SecurityException("Checkout Cart owner mismatch");
        if (cart.revision() != request.cartRevision())
            throw rejected(CheckoutRejectedException.Reason.CART_REVISION_MISMATCH, "Cart changed; reload before Checkout");

        List<SelectedLine> selected = selectedLines(cart, request);
        Instant observedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var orderLines = new ArrayList<OrderLineSnapshot>(selected.size());
        var courseIds = new TreeSet<Integer>();
        var productQuantities = new TreeMap<Integer, Integer>();

        for (SelectedLine selection : selected) {
            CartState.Item item = selection.cartItem();
            if (item.itemType() == CartItemType.COURSE) {
                var offer = courses.findForCheckout(item.itemId()).orElseThrow(() -> rejected(
                        CheckoutRejectedException.Reason.ITEM_UNAVAILABLE, "Course is unavailable"));
                if (!offer.purchasable()) throw rejected(CheckoutRejectedException.Reason.ITEM_UNAVAILABLE, "Course is unavailable");
                if (coursePurchases.isOwnedBy(userId, item.itemId()))
                    throw rejected(CheckoutRejectedException.Reason.COURSE_ALREADY_OWNED, "Course is already owned");
                if (coursePurchases.hasActivePendingPurchase(userId, item.itemId(), observedAt))
                    throw rejected(CheckoutRejectedException.Reason.COURSE_PENDING_PURCHASE, "Course has an active pending purchase");
                courseIds.add(item.itemId());
                orderLines.add(new OrderLineSnapshot(OrderItemType.COURSE, item.itemId(), offer.name(),
                        item.quantity(), offer.unitPriceVnd(), true));
            } else {
                var offer = products.findForCheckout(item.itemId()).orElseThrow(() -> rejected(
                        CheckoutRejectedException.Reason.ITEM_UNAVAILABLE, "Product is unavailable"));
                if (!offer.purchasable()) throw rejected(CheckoutRejectedException.Reason.ITEM_UNAVAILABLE, "Product is unavailable");
                productQuantities.merge(item.itemId(), item.quantity(), (left, right) -> Math.addExact(left, right));
                orderLines.add(new OrderLineSnapshot(OrderItemType.PRODUCT, item.itemId(), offer.name(),
                        item.quantity(), offer.unitPriceVnd(), true));
            }
        }

        long eligibleSubtotal = 0L;
        for (var line : orderLines) if (line.voucherEligible())
            eligibleSubtotal = Math.addExact(eligibleSubtotal, line.grossAmountVnd());
        final long eligibleMerchandiseVnd = eligibleSubtotal;

        Optional<VoucherReservationPort.Quote> voucherQuote = request.voucherCode().map(code ->
                vouchers.quoteAndLock(userId, code, eligibleMerchandiseVnd, observedAt)
                        .orElseThrow(() -> rejected(CheckoutRejectedException.Reason.VOUCHER_REJECTED, "Voucher is unavailable")));
        Optional<com.drawingcourse.backend.entity.OrderVoucher> voucherTerms = voucherQuote.map(VoucherReservationPort.Quote::terms);

        long shippingFee = 0L;
        if (!productQuantities.isEmpty()) {
            var shippingItems = productQuantities.entrySet().stream()
                    .map(entry -> new ShippingQuotePort.ProductQuantity(entry.getKey(), entry.getValue())).toList();
            shippingFee = shipping.quoteFeeVnd(userId, shippingItems, request.address());
            if (shippingFee < 0) throw rejected(CheckoutRejectedException.Reason.SHIPPING_QUOTE_REJECTED,
                    "Shipping provider returned an invalid fee");
        }

        // Domain snapshot is immutable and whole-VND. Persist in this transaction to obtain the Order ID required
        // by existing voucher_claims and the proposed Course/Inventory reservation contracts.
        final Order snapshot;
        try {
            snapshot = Order.create(userId, orderLines, shippingFee, voucherTerms, clock);
        } catch (IllegalArgumentException invalidVoucherOrSnapshot) {
            if (voucherQuote.isPresent()) throw new CheckoutRejectedException(
                    CheckoutRejectedException.Reason.VOUCHER_REJECTED, "Voucher cannot be applied to this Cart", invalidVoucherOrSnapshot);
            throw invalidVoucherOrSnapshot;
        }
        StoredOrder stored = orders.insertNew(snapshot);
        if (stored == null || stored.id() == null || !userId.equals(stored.snapshot().getUserId()))
            throw new IllegalStateException("Order persistence returned an invalid owner/reference");

        // Providers acquire shared rows in deterministic ID order. All must join this transaction and perform
        // atomic write-side checks; the earlier ownership/pending/catalog reads are not concurrency protection.
        for (Integer productId : productQuantities.keySet())
            inventory.reserveForOrder(userId, productId, productQuantities.get(productId), stored.id(), snapshot.getExpiresAt());
        for (Integer courseId : courseIds)
            coursePurchases.claimForOrder(userId, courseId, stored.id(), observedAt, snapshot.getExpiresAt());
        voucherQuote.ifPresent(quote -> vouchers.reserveForOrder(userId, stored, snapshot, quote,
                clock.instant().truncatedTo(ChronoUnit.MICROS)));

        idempotency.complete(userId, requestId, stored.id());
        return new CheckoutResult(stored.id(), false);
    }

    private static List<SelectedLine> selectedLines(CartState cart, CheckoutRequest request) {
        Map<Integer, CartState.Item> byId = new HashMap<>();
        for (CartState.Item item : cart.items()) byId.put(item.cartItemId(), item);
        var selected = new ArrayList<SelectedLine>(request.selections().size());
        for (CheckoutRequest.Selection choice : request.selections()) {
            CartState.Item item = byId.get(choice.cartItemId());
            if (item == null) throw rejected(CheckoutRejectedException.Reason.CART_ITEM_MISSING, "Selected Cart item not found");
            if (item.quantity() != choice.quantity()) throw rejected(
                    CheckoutRejectedException.Reason.CART_SELECTION_MISMATCH, "Selected quantity does not match the locked Cart");
            selected.add(new SelectedLine(item));
        }
        if (selected.isEmpty()) throw rejected(CheckoutRejectedException.Reason.CART_SELECTION_MISMATCH, "Checkout selection is empty");
        return List.copyOf(selected);
    }

    private static CheckoutRejectedException rejected(CheckoutRejectedException.Reason reason, String message) {
        return new CheckoutRejectedException(reason, message);
    }

    private record SelectedLine(CartState.Item cartItem) { }
    public record CheckoutResult(Integer orderId, boolean replayed) {
        public CheckoutResult { if (orderId == null || orderId <= 0) throw new IllegalArgumentException("Invalid Order ID"); }
    }
}
