package com.artacademy.supply.fulfillment;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.artacademy.product.Product;
import com.artacademy.supply.api.CartProductView;
import com.artacademy.supply.api.ReservationView;
import com.artacademy.supply.api.ReserveLine;
import com.artacademy.supply.api.ShippingInfo;
import com.artacademy.supply.api.SupplyFulfillmentApi;
import com.artacademy.supply.catalog.StockSnapshot;
import com.artacademy.supply.catalog.SupplyProductRepository;
import com.artacademy.supply.common.SupplyException;
import com.artacademy.supply.inventory.ReservationStatus;
import com.artacademy.supply.inventory.StockLedger;
import com.artacademy.supply.inventory.StockReservation;
import com.artacademy.supply.inventory.StockReservationRepository;
import com.artacademy.supply.shipment.Shipment;
import com.artacademy.supply.shipment.ShipmentItem;
import com.artacademy.supply.shipment.ShipmentRepository;
import com.artacademy.supply.shipment.ShipmentStatus;

@Service
public class SupplyFulfillmentService implements SupplyFulfillmentApi {

    private static final int MAX_QUANTITY_PER_LINE = 99;

    private final SupplyProductRepository productRepository;
    private final StockReservationRepository reservationRepository;
    private final ShipmentRepository shipmentRepository;
    private final StockLedger ledger;

    public SupplyFulfillmentService(SupplyProductRepository productRepository,
            StockReservationRepository reservationRepository, ShipmentRepository shipmentRepository,
            StockLedger ledger) {
        this.productRepository = productRepository;
        this.reservationRepository = reservationRepository;
        this.shipmentRepository = shipmentRepository;
        this.ledger = ledger;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<CartProductView> findProductForCart(Long productId) {
        return productRepository.findById(productId).map(p -> new CartProductView(p.getId(), p.getSku(),
                p.getName(), p.getPrice(), p.getThumbnailUrl(), p.getAvailable(), p.isActive()));
    }

    @Override
    @Transactional
    public List<ReservationView> reserveForOrder(Integer orderId, Integer userId, List<ReserveLine> lines) {
        if (orderId == null || userId == null) {
            throw SupplyException.badRequest("INVALID_ORDER", "Thiếu mã đơn hoặc người mua");
        }
        List<StockReservation> existing = reservationRepository.findByOrderIdWithProduct(orderId);
        if (!existing.isEmpty()) {
            return reuseExisting(orderId, userId, existing);
        }
        if (lines == null || lines.isEmpty()) {
            return List.of();
        }

        // Gộp các dòng trùng, sắp theo productId để các đơn khóa dòng theo cùng thứ tự (tránh deadlock)
        Map<LineKey, Integer> merged = new TreeMap<>();
        for (ReserveLine line : lines) {
            if (line == null || line.productId() == null || line.quantity() <= 0) {
                throw SupplyException.badRequest("INVALID_QUANTITY", "Số lượng học cụ phải lớn hơn 0");
            }
            merged.merge(new LineKey(line.productId(), line.gift()), line.quantity(), Integer::sum);
        }
        merged.forEach((key, qty) -> {
            if (qty > MAX_QUANTITY_PER_LINE) {
                throw SupplyException.badRequest("INVALID_QUANTITY",
                        "Mỗi học cụ tối đa " + MAX_QUANTITY_PER_LINE + " cái mỗi đơn");
            }
        });

        Map<Long, Product> products = productRepository
                .findAllById(merged.keySet().stream().map(LineKey::productId).distinct().toList())
                .stream().collect(Collectors.toMap(Product::getId, Function.identity()));

        for (LineKey key : merged.keySet()) {
            Product p = products.get(key.productId());
            if (p == null) {
                throw SupplyException.notFound("Học cụ #" + key.productId());
            }
            // Quà đã cấu hình trước đó vẫn được giao dù sản phẩm vừa bị ẩn
            if (!key.gift() && !p.isActive()) {
                throw SupplyException.conflict("PRODUCT_UNAVAILABLE",
                        "Học cụ \"" + p.getName() + "\" đã ngừng bán");
            }
        }

        List<StockReservation> created = new ArrayList<>();
        for (Map.Entry<LineKey, Integer> e : merged.entrySet()) {
            LineKey key = e.getKey();
            int qty = e.getValue();
            Product p = products.get(key.productId());
            if (!ledger.tryReserve(key.productId(), qty, orderId, key.gift())) {
                throw outOfStock(p, qty);
            }
            StockReservation r = new StockReservation();
            r.setOrderId(orderId);
            r.setUserId(userId);
            r.setProduct(productRepository.getReferenceById(key.productId()));
            r.setQuantity(qty);
            r.setGift(key.gift());
            created.add(r);
        }
        reservationRepository.saveAll(created);
        reservationRepository.flush();

        return merged.entrySet().stream().map(e -> {
            Product p = products.get(e.getKey().productId());
            return new ReservationView(p.getId(), p.getSku(), p.getName(), e.getValue(), e.getKey().gift(),
                    ReservationStatus.RESERVED.name());
        }).toList();
    }

    /** Đơn đã từng giữ: trả lại kết quả cũ, hoặc giữ lại nếu trước đó đã release (thanh toán báo muộn). */
    private List<ReservationView> reuseExisting(Integer orderId, Integer userId, List<StockReservation> existing) {
        if (!existing.get(0).getUserId().equals(userId)) {
            throw SupplyException.conflict("ORDER_MISMATCH", "Mã đơn đã được dùng cho người khác");
        }
        boolean allReleased = existing.stream().allMatch(r -> r.getStatus() == ReservationStatus.RELEASED);
        if (!allReleased) {
            return existing.stream().map(SupplyFulfillmentService::toView).toList();
        }
        LocalDateTime now = LocalDateTime.now();
        for (StockReservation r : existing) {
            Product p = r.getProduct();
            if (!ledger.tryReserve(p.getId(), r.getQuantity(), orderId, r.isGift())) {
                throw outOfStock(p, r.getQuantity());
            }
            if (reservationRepository.transition(r.getId(), ReservationStatus.RELEASED, ReservationStatus.RESERVED,
                    now) == 0) {
                throw SupplyException.conflict("RESERVATION_CHANGED", "Đơn đang được xử lý, vui lòng thử lại");
            }
        }
        return reservationRepository.findByOrderIdWithProduct(orderId).stream()
                .map(SupplyFulfillmentService::toView).toList();
    }

    @Override
    @Transactional
    public int releaseForOrder(Integer orderId, String reason) {
        Optional<Shipment> shipment = shipmentRepository.findByOrderId(orderId);
        if (shipment.isPresent()) {
            Shipment s = shipment.get();
            if (s.getStatus() == ShipmentStatus.PREPARING) {
                // Khóa chuyến giao trước, để không chạy song song với nhân viên bấm "Đang giao"
                if (shipmentRepository.transition(s.getId(), ShipmentStatus.PREPARING, ShipmentStatus.CANCELLED,
                        LocalDateTime.now()) == 0) {
                    throw alreadyShipped();
                }
                Shipment reloaded = shipmentRepository.findById(s.getId()).orElseThrow();
                reloaded.setCancelledAt(LocalDateTime.now());
                reloaded.setNote(reason);
            } else if (s.getStatus() != ShipmentStatus.CANCELLED) {
                throw alreadyShipped();
            }
        }

        int released = 0;
        LocalDateTime now = LocalDateTime.now();
        for (StockReservation r : reservationRepository.findByOrderIdWithProduct(orderId)) {
            if (r.getStatus() != ReservationStatus.RESERVED) {
                continue;
            }
            if (reservationRepository.transition(r.getId(), ReservationStatus.RESERVED, ReservationStatus.RELEASED,
                    now) == 1) {
                ledger.releaseReserved(r.getProduct().getId(), r.getQuantity(), orderId, r.isGift(), reason);
                released++;
            }
        }
        return released;
    }

    @Override
    @Transactional
    public Optional<Long> onOrderPaid(Integer orderId, Integer userId, ShippingInfo info) {
        Optional<Shipment> existingShipment = shipmentRepository.findByOrderId(orderId);
        if (existingShipment.isPresent()) {
            return Optional.of(existingShipment.get().getId());
        }
        List<StockReservation> reservations = reservationRepository.findByOrderIdWithProduct(orderId);
        if (reservations.isEmpty()) {
            return Optional.empty(); // đơn thuần khóa học, không cần giao
        }
        if (!reservations.get(0).getUserId().equals(userId)) {
            throw SupplyException.conflict("ORDER_MISMATCH", "Đơn không thuộc người mua này");
        }
        List<StockReservation> held = reservations.stream()
                .filter(r -> r.getStatus() == ReservationStatus.RESERVED).toList();
        if (held.isEmpty()) {
            throw SupplyException.conflict("RESERVATION_RELEASED",
                    "Hàng giữ cho đơn đã được trả lại; cần gọi reserveForOrder để giữ lại trước");
        }
        if (info == null || isBlank(info.recipientName()) || isBlank(info.phone()) || isBlank(info.address())) {
            throw SupplyException.badRequest("SHIPPING_INFO_REQUIRED", "Đơn có học cụ cần địa chỉ giao hàng");
        }

        Shipment s = new Shipment();
        s.setOrderId(orderId);
        s.setUserId(userId);
        s.setRecipientName(info.recipientName().trim());
        s.setRecipientPhone(info.phone().trim());
        s.setShippingAddress(info.address().trim());
        held.stream().sorted(Comparator.comparing(StockReservation::isGift)).forEach(r -> {
            Product p = r.getProduct();
            ShipmentItem item = new ShipmentItem();
            item.setProductId(p.getId());
            item.setReservationId(r.getId());
            item.setSku(p.getSku());
            item.setProductName(p.getName());
            item.setThumbnailUrl(p.getThumbnailUrl());
            item.setQuantity(r.getQuantity());
            item.setGift(r.isGift());
            s.addItem(item);
        });
        return Optional.of(shipmentRepository.saveAndFlush(s).getId());
    }

    private SupplyException outOfStock(Product p, int wanted) {
        int available = productRepository.findStock(p.getId()).map(StockSnapshot::available).orElse(0);
        String msg = available == 0
                ? "Học cụ \"" + p.getName() + "\" đã hết hàng"
                : "Học cụ \"" + p.getName() + "\" chỉ còn " + available + ", không đủ " + wanted;
        return SupplyException.conflict("OUT_OF_STOCK", msg);
    }

    private static SupplyException alreadyShipped() {
        return SupplyException.conflict("ALREADY_SHIPPED", "Hàng đã xuất kho, không thể hủy phần học cụ");
    }

    private static ReservationView toView(StockReservation r) {
        Product p = r.getProduct();
        return new ReservationView(p.getId(), p.getSku(), p.getName(), r.getQuantity(), r.isGift(),
                r.getStatus().name());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private record LineKey(Long productId, boolean gift) implements Comparable<LineKey> {
        @Override
        public int compareTo(LineKey o) {
            int c = productId.compareTo(o.productId);
            return c != 0 ? c : Boolean.compare(gift, o.gift);
        }
    }
}
