package com.drawingcourse.backend.service.checkout;

import com.drawingcourse.backend.entity.CartItemType;
import com.drawingcourse.backend.entity.ComboCheckoutValidator;
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
    private final boolean courseOnlyMode;
    /** Combo participants exist only in Course-only mode and only when explicitly wired; otherwise null. */
    private final ComboCatalogPort combos;
    private final ComboPurchasePort comboPurchases;

    public CheckoutCoordinator(CheckoutIdempotencyPort idempotency, CheckoutCartPort carts,
            CourseCatalogPort courses, ProductCatalogPort products, CoursePurchasePort coursePurchases,
            InventoryReservationPort inventory, VoucherReservationPort vouchers, ShippingQuotePort shipping,
            CheckoutOrderPort orders, Clock clock, PlatformTransactionManager transactionManager) {
        this(idempotency, carts, courses, products, coursePurchases, inventory, vouchers, shipping,
                orders, clock, transactionManager, false, null, null);
    }

    private CheckoutCoordinator(CheckoutIdempotencyPort idempotency, CheckoutCartPort carts,
            CourseCatalogPort courses, ProductCatalogPort products, CoursePurchasePort coursePurchases,
            InventoryReservationPort inventory, VoucherReservationPort vouchers, ShippingQuotePort shipping,
            CheckoutOrderPort orders, Clock clock, PlatformTransactionManager transactionManager,
            boolean courseOnlyMode, ComboCatalogPort combos, ComboPurchasePort comboPurchases) {
        if ((combos == null) != (comboPurchases == null))
            throw new IllegalArgumentException("Combo catalog and purchase participants must be wired together");
        this.combos = combos;
        this.comboPurchases = comboPurchases;
        this.idempotency = Objects.requireNonNull(idempotency);
        this.carts = Objects.requireNonNull(carts);
        this.courses = Objects.requireNonNull(courses);
        this.coursePurchases = Objects.requireNonNull(coursePurchases);
        this.vouchers = Objects.requireNonNull(vouchers);
        this.courseOnlyMode = courseOnlyMode;
        if (courseOnlyMode) {
            this.products = products;
            this.inventory = inventory;
            this.shipping = shipping;
        } else {
            this.products = Objects.requireNonNull(products);
            this.inventory = Objects.requireNonNull(inventory);
            this.shipping = Objects.requireNonNull(shipping);
        }
        this.orders = Objects.requireNonNull(orders);
        this.clock = Objects.requireNonNull(clock);
        this.transaction = new TransactionTemplate(Objects.requireNonNull(transactionManager));
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRED);
    }

    /**
     * General (Course + Product + Combo) coordinator: like the plain constructor, and a Combo line also sells its
     * Courses (one Order line, one claim per Course) in the same Order and transaction as the Products.
     */
    public CheckoutCoordinator(CheckoutIdempotencyPort idempotency, CheckoutCartPort carts,
            CourseCatalogPort courses, ProductCatalogPort products, CoursePurchasePort coursePurchases,
            InventoryReservationPort inventory, VoucherReservationPort vouchers, ShippingQuotePort shipping,
            CheckoutOrderPort orders, Clock clock, PlatformTransactionManager transactionManager,
            ComboCatalogPort combos, ComboPurchasePort comboPurchases) {
        this(idempotency, carts, courses, products, coursePurchases, inventory, vouchers, shipping,
                orders, clock, transactionManager, false, combos, comboPurchases);
    }

    /** Constructs a Course-only coordinator without fake Product, Inventory, or Shipping participants. */
    public static CheckoutCoordinator courseOnly(CheckoutIdempotencyPort idempotency, CheckoutCartPort carts,
            CourseCatalogPort courses, CoursePurchasePort coursePurchases, VoucherReservationPort vouchers,
            CheckoutOrderPort orders, Clock clock, PlatformTransactionManager transactionManager) {
        return new CheckoutCoordinator(idempotency, carts, courses, null, coursePurchases, null, vouchers, null,
                orders, clock, transactionManager, true, null, null);
    }

    /**
     * Course-only coordinator that also accepts Combo lines: one Order line per Combo, a claim per component Course.
     * Combos carry no voucher eligibility and no Product/Inventory/Shipping participants.
     */
    public static CheckoutCoordinator courseOnlyWithCombos(CheckoutIdempotencyPort idempotency,
            CheckoutCartPort carts, CourseCatalogPort courses, CoursePurchasePort coursePurchases,
            ComboCatalogPort combos, ComboPurchasePort comboPurchases, VoucherReservationPort vouchers,
            CheckoutOrderPort orders, Clock clock, PlatformTransactionManager transactionManager) {
        return new CheckoutCoordinator(idempotency, carts, courses, null, coursePurchases, null, vouchers, null,
                orders, clock, transactionManager, true, Objects.requireNonNull(combos, "combos"),
                Objects.requireNonNull(comboPurchases, "comboPurchases"));
    }

    /** authenticatedUserId must be resolved from the verified principal at the application boundary. */
    public CheckoutResult checkout(Integer authenticatedUserId, String idempotencyKey, CheckoutRequest request) {
        if (courseOnlyMode) return checkoutCoursesOnly(authenticatedUserId, idempotencyKey, request);
        return checkoutInternal(authenticatedUserId, idempotencyKey, request, false);
    }

    /** Enforces Course-only selection under the same Cart lock used for the actual Checkout. */
    public CheckoutResult checkoutCoursesOnly(Integer authenticatedUserId, String idempotencyKey,
                                               CheckoutRequest request) {
        if (!courseOnlyMode) throw new IllegalStateException("Coordinator is not configured for Course-only Checkout");
        return checkoutInternal(authenticatedUserId, idempotencyKey, request, true);
    }

    private CheckoutResult checkoutInternal(Integer authenticatedUserId, String idempotencyKey,
                                             CheckoutRequest request, boolean courseOnly) {
        if (authenticatedUserId == null || authenticatedUserId <= 0)
            throw new IllegalArgumentException("Authenticated user ID must be positive");
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 64)
            throw rejected(CheckoutRejectedException.Reason.INVALID_IDEMPOTENCY_KEY, "Idempotency-Key must contain 1 to 64 characters");
        Objects.requireNonNull(request, "request");
        return transaction.execute(status -> checkoutInTransaction(authenticatedUserId, idempotencyKey,
                request, courseOnly));
    }

    private CheckoutResult checkoutInTransaction(Integer userId, String key, CheckoutRequest request,
                                                  boolean courseOnly) {
        String fingerprint = courseOnly ? request.canonicalFingerprint("course-only-v1")
                : request.canonicalFingerprint();
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
        if (courseOnly && selected.stream().anyMatch(line -> line.cartItem().itemType() == CartItemType.PRODUCT)) {
            throw rejected(CheckoutRejectedException.Reason.UNSUPPORTED_ITEM_TYPE,
                    "Course-only Checkout does not support Product items");
        }
        // Combos are Course bundles: only a coordinator wired with Combo participants can sell them. A Combo ID
        // must never fall through to the Product branch below.
        if (combos == null
                && selected.stream().anyMatch(line -> line.cartItem().itemType() == CartItemType.COMBO)) {
            throw rejected(CheckoutRejectedException.Reason.UNSUPPORTED_ITEM_TYPE,
                    "This Checkout does not support Combo items");
        }
        Instant observedAt = clock.instant().truncatedTo(ChronoUnit.MICROS);
        var orderLines = new ArrayList<OrderLineSnapshot>(selected.size());
        var courseIds = new TreeSet<Integer>();
        var comboOffers = new TreeMap<Integer, ComboCatalogPort.ComboOffer>();
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
            } else if (item.itemType() == CartItemType.COMBO) {
                // One Order line per Combo at the Combo's own price. Not voucher-eligible (D's rule): the Combo price
                // is already the discount. Read once; this offer is the immutable snapshot used for the claims below.
                var offer = combos.findForCheckout(item.itemId()).orElseThrow(() -> rejected(
                        CheckoutRejectedException.Reason.ITEM_UNAVAILABLE, "Combo is unavailable"));
                if (!offer.purchasable() || !offer.comboId().equals(item.itemId()))
                    throw rejected(CheckoutRejectedException.Reason.ITEM_UNAVAILABLE, "Combo is unavailable");
                comboOffers.put(offer.comboId(), offer);
                orderLines.add(new OrderLineSnapshot(OrderItemType.COMBO, offer.comboId(), offer.name(), 1,
                        offer.unitPriceVnd(), false));
            } else {
                if (courseOnly) throw rejected(CheckoutRejectedException.Reason.UNSUPPORTED_ITEM_TYPE,
                        "Course-only Checkout does not support Product items");
                var offer = products.findForCheckout(item.itemId()).orElseThrow(() -> rejected(
                        CheckoutRejectedException.Reason.ITEM_UNAVAILABLE, "Product is unavailable"));
                if (!offer.purchasable()) throw rejected(CheckoutRejectedException.Reason.ITEM_UNAVAILABLE, "Product is unavailable");
                productQuantities.merge(item.itemId(), item.quantity(), (left, right) -> Math.addExact(left, right));
                orderLines.add(new OrderLineSnapshot(OrderItemType.PRODUCT, item.itemId(), offer.name(),
                        item.quantity(), offer.unitPriceVnd(), true));
            }
        }

        if (!comboOffers.isEmpty()) validateComboCoverage(userId, courseIds, comboOffers.values(), observedAt);

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
            if (shipping == null) throw new IllegalStateException("Product Checkout requires a Shipping adapter");
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
        if (!productQuantities.isEmpty() && request.address().isEmpty())
            throw rejected(CheckoutRejectedException.Reason.SHIPPING_QUOTE_REJECTED,
                    "Physical Order requires a shipping address");
        StoredOrder stored = orders.insertNew(snapshot, request.address());
        if (stored == null || stored.id() == null || !userId.equals(stored.snapshot().getUserId()))
            throw new IllegalStateException("Order persistence returned an invalid owner/reference");

        Map<Integer, Integer> courseOrderItemIds = persistedCourseOrderItems(stored, courseIds);
        Map<Integer, Integer> comboOrderItemIds = persistedOrderItems(stored, OrderItemType.COMBO, comboOffers.keySet());

        // Providers acquire shared rows in deterministic ID order. All must join this transaction and perform
        // atomic write-side checks; the earlier ownership/pending/catalog reads are not concurrency protection.
        if (!productQuantities.isEmpty() && inventory == null)
            throw new IllegalStateException("Product Checkout requires an Inventory adapter");
        if (!productQuantities.isEmpty())
            inventory.reserveAllForOrder(userId, productQuantities, stored.id(), snapshot.getExpiresAt());
        for (Integer courseId : courseIds)
            coursePurchases.claimForOrder(userId, courseId, stored.id(), courseOrderItemIds.get(courseId),
                    observedAt, snapshot.getExpiresAt());
        // Each Combo reserves all of its Courses atomically in this same transaction (all or nothing).
        for (var combo : comboOffers.values())
            comboPurchases.claimComboForOrder(userId, stored.id(), comboOrderItemIds.get(combo.comboId()),
                    combo.components(), observedAt, snapshot.getExpiresAt());
        voucherQuote.ifPresent(quote -> vouchers.reserveForOrder(userId, stored, snapshot, quote,
                clock.instant().truncatedTo(ChronoUnit.MICROS)));

        idempotency.complete(userId, requestId, stored.id());
        return new CheckoutResult(stored.id(), false);
    }

    /**
     * Validates the whole selection as trusted facts through the pure {@link ComboCheckoutValidator}: no Course may be
     * covered twice (standalone and in a Combo, or in two Combos), owned, or have an active pending purchase.
     */
    private void validateComboCoverage(Integer userId, Set<Integer> standaloneCourseIds,
            Collection<ComboCatalogPort.ComboOffer> offers, Instant observedAt) {
        var covered = new TreeSet<>(standaloneCourseIds);
        var comboLines = new ArrayList<ComboCheckoutValidator.SelectedComboLine>();
        for (var offer : offers) {
            var ordered = offer.components().stream()
                    .sorted(Comparator.comparingInt(ComboCatalogPort.Component::position))
                    .map(ComboCatalogPort.Component::courseId).toList();
            covered.addAll(ordered);
            comboLines.add(new ComboCheckoutValidator.SelectedComboLine(new ComboCheckoutValidator.ComboDefinition(
                    offer.comboId(), offer.definitionVersion(), offer.name(), offer.unitPriceVnd(), ordered), 1));
        }
        var owned = new TreeSet<Integer>();
        var pending = new TreeSet<Integer>();
        for (Integer courseId : covered) {
            if (coursePurchases.isOwnedBy(userId, courseId)) owned.add(courseId);
            if (coursePurchases.hasActivePendingPurchase(userId, courseId, observedAt)) pending.add(courseId);
        }
        var standalone = standaloneCourseIds.stream()
                .map(id -> new ComboCheckoutValidator.SelectedStandaloneCourseLine(id, 1)).toList();
        var result = ComboCheckoutValidator.validate(
                new ComboCheckoutValidator.ValidationInput(standalone, comboLines, owned, pending));
        if (result instanceof ComboCheckoutValidator.Invalid invalid) {
            switch (invalid.failure().code()) {
                case COURSE_ALREADY_OWNED -> throw rejected(CheckoutRejectedException.Reason.COURSE_ALREADY_OWNED,
                        "A Course in this Combo is already owned");
                case COURSE_PENDING_PURCHASE -> throw rejected(
                        CheckoutRejectedException.Reason.COURSE_PENDING_PURCHASE,
                        "A Course in this Combo has an active pending purchase");
                case DUPLICATE_COURSE_COVERAGE, DUPLICATE_COMBO_COMPONENT -> throw rejected(
                        CheckoutRejectedException.Reason.COMBO_COURSE_OVERLAP,
                        "A Combo covers a Course that is already selected");
                default -> throw rejected(CheckoutRejectedException.Reason.ITEM_UNAVAILABLE, "Combo is unavailable");
            }
        }
    }

    private static Map<Integer, Integer> persistedOrderItems(StoredOrder stored, OrderItemType type,
                                                             Set<Integer> expectedItemIds) {
        Map<Integer, Integer> itemIds = new TreeMap<>();
        for (StoredOrder.ItemIdentity identity : stored.itemIdentities()) {
            if (identity.itemType() != type) continue;
            if (identity.id() == null || identity.id() <= 0 || identity.itemId() == null || identity.itemId() <= 0)
                throw new IllegalStateException("Order persistence returned an invalid " + type + " OrderItem identity");
            if (itemIds.putIfAbsent(identity.itemId(), identity.id()) != null)
                throw new IllegalStateException("Order persistence returned duplicate " + type + " OrderItems");
        }
        if (!itemIds.keySet().equals(expectedItemIds))
            throw new IllegalStateException("Persisted " + type + " OrderItems do not match Checkout selection");
        return itemIds;
    }

    private static Map<Integer, Integer> persistedCourseOrderItems(StoredOrder stored, Set<Integer> courseIds) {
        Map<Integer, Integer> itemIdsByCourse = new TreeMap<>();
        for (StoredOrder.ItemIdentity identity : stored.itemIdentities()) {
            if (identity.itemType() != OrderItemType.COURSE) continue;
            if (identity.id() == null || identity.id() <= 0 || identity.itemId() == null || identity.itemId() <= 0)
                throw new IllegalStateException("Order persistence returned an invalid Course OrderItem identity");
            if (itemIdsByCourse.putIfAbsent(identity.itemId(), identity.id()) != null)
                throw new IllegalStateException("Order persistence returned duplicate Course OrderItems");
        }
        if (!itemIdsByCourse.keySet().equals(courseIds))
            throw new IllegalStateException("Persisted Course OrderItems do not match Checkout selection");
        return itemIdsByCourse;
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
