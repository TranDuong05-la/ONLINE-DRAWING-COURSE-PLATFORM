package com.artacademy.supply.shipment;

import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.artacademy.supply.common.PageResult;
import com.artacademy.supply.common.SupplyException;
import com.artacademy.supply.common.SupplyUser;
import com.artacademy.supply.inventory.ReservationStatus;
import com.artacademy.supply.inventory.StockLedger;
import com.artacademy.supply.inventory.StockReservation;
import com.artacademy.supply.inventory.StockReservationRepository;
import com.artacademy.supply.shipment.ShipmentDtos.ShipmentResponse;
import com.artacademy.supply.shipment.ShipmentDtos.ShipmentSummary;
import com.artacademy.supply.shipment.ShipmentDtos.UpdateShipmentStatusRequest;

@Service
public class ShipmentService {

    private final ShipmentRepository shipmentRepository;
    private final StockReservationRepository reservationRepository;
    private final StockLedger ledger;

    public ShipmentService(ShipmentRepository shipmentRepository, StockReservationRepository reservationRepository,
            StockLedger ledger) {
        this.shipmentRepository = shipmentRepository;
        this.reservationRepository = reservationRepository;
        this.ledger = ledger;
    }

    // ---------- Học viên ----------

    @Transactional(readOnly = true)
    public List<ShipmentResponse> listMine(SupplyUser user) {
        return shipmentRepository.findByUserIdOrderByCreatedAtDesc(user.id()).stream()
                .map(s -> ShipmentResponse.of(s, false)).toList();
    }

    @Transactional(readOnly = true)
    public ShipmentResponse getForUser(Long id, SupplyUser user) {
        Shipment s = shipmentRepository.findWithItemsById(id)
                .orElseThrow(() -> SupplyException.notFound("Đơn giao hàng"));
        checkOwner(s, user);
        return ShipmentResponse.of(s, user.canManage());
    }

    /** D dùng ở màn chi tiết đơn để hiện trạng thái giao. */
    @Transactional(readOnly = true)
    public ShipmentResponse getByOrder(Integer orderId, SupplyUser user) {
        Shipment s = shipmentRepository.findByOrderId(orderId)
                .orElseThrow(() -> SupplyException.notFound("Đơn giao hàng"));
        checkOwner(s, user);
        return ShipmentResponse.of(s, user.canManage());
    }

    private static void checkOwner(Shipment s, SupplyUser user) {
        if (!user.canManage() && !s.getUserId().equals(user.id())) {
            // Trả 404 để không lộ đơn của người khác có tồn tại hay không
            throw SupplyException.notFound("Đơn giao hàng");
        }
    }

    // ---------- Nhân viên / admin ----------

    @Transactional(readOnly = true)
    public PageResult<ShipmentResponse> listForManager(List<ShipmentStatus> statuses, int page, int size) {
        var statusSet = statuses == null || statuses.isEmpty() ? EnumSet.allOf(ShipmentStatus.class)
                : EnumSet.copyOf(statuses);
        // Hàng cần đóng gói: đơn cũ nhất trước. Các trạng thái khác: mới cập nhật trước.
        Sort sort = statusSet.equals(EnumSet.of(ShipmentStatus.PREPARING))
                ? Sort.by(Sort.Direction.ASC, "createdAt")
                : Sort.by(Sort.Direction.DESC, "updatedAt");
        var result = shipmentRepository.findByStatusIn(statusSet,
                PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 100), sort.and(Sort.by("id"))));
        return PageResult.of(result, s -> ShipmentResponse.of(s, true));
    }

    @Transactional(readOnly = true)
    public ShipmentSummary summary() {
        return new ShipmentSummary(
                shipmentRepository.countByStatus(ShipmentStatus.PREPARING),
                shipmentRepository.countByStatus(ShipmentStatus.SHIPPING),
                shipmentRepository.countByStatus(ShipmentStatus.DELIVERED),
                shipmentRepository.countByStatus(ShipmentStatus.FAILED),
                shipmentRepository.countByStatus(ShipmentStatus.RETURNED),
                shipmentRepository.countByStatus(ShipmentStatus.CANCELLED));
    }

    /**
     * Đổi trạng thái giao.
     * PREPARING -> SHIPPING: xuất kho (đúng một lần nhờ đổi trạng thái giữ hàng có điều kiện).
     * FAILED: không tự nhập kho, vì hàng có thể chưa về. Chọn RETURNED khi hàng thực sự về kho.
     * FAILED -> SHIPPING: giao lại, không xuất kho lần nữa.
     */
    @Transactional
    public ShipmentResponse updateStatus(Long id, UpdateShipmentStatusRequest req, SupplyUser actor) {
        Shipment current = shipmentRepository.findWithItemsById(id)
                .orElseThrow(() -> SupplyException.notFound("Đơn giao hàng"));
        ShipmentStatus from = current.getStatus();
        ShipmentStatus to = req.status();
        if (!from.nextByAdmin().contains(to)) {
            throw SupplyException.badRequest("INVALID_TRANSITION",
                    "Không thể chuyển từ \"" + label(from) + "\" sang \"" + label(to) + "\"");
        }
        LocalDateTime now = LocalDateTime.now();
        if (shipmentRepository.transition(id, from, to, now) == 0) {
            throw SupplyException.conflict("STATUS_CHANGED",
                    "Trạng thái vừa được cập nhật ở nơi khác, vui lòng tải lại");
        }

        if (to == ShipmentStatus.SHIPPING && from == ShipmentStatus.PREPARING) {
            exportStock(current, actor.id());
        }
        if (to == ShipmentStatus.RETURNED) {
            for (ShipmentItem item : current.getItems()) {
                ledger.returnToStock(item.getProductId(), item.getQuantity(), current.getOrderId(), id,
                        item.isGift(), "Hàng giao thất bại đã về kho", actor.id());
            }
        }

        Shipment s = shipmentRepository.findWithItemsById(id).orElseThrow();
        switch (to) {
            case SHIPPING -> {
                s.setShippedAt(now);
                s.setAttempts(s.getAttempts() + 1);
            }
            case DELIVERED -> s.setDeliveredAt(now);
            case FAILED -> s.setFailedAt(now);
            case RETURNED -> s.setReturnedAt(now);
            default -> {
            }
        }
        if (req.carrier() != null) {
            s.setCarrier(blankToNull(req.carrier()));
        }
        if (req.trackingCode() != null) {
            s.setTrackingCode(blankToNull(req.trackingCode()));
        }
        if (req.note() != null) {
            s.setNote(blankToNull(req.note()));
        }
        shipmentRepository.flush();
        return ShipmentResponse.of(s, true);
    }

    private void exportStock(Shipment shipment, Integer actorId) {
        Map<Long, StockReservation> reservations = reservationRepository
                .findByOrderIdWithProduct(shipment.getOrderId()).stream()
                .collect(Collectors.toMap(StockReservation::getId, Function.identity()));
        LocalDateTime now = LocalDateTime.now();
        for (ShipmentItem item : shipment.getItems()) {
            StockReservation r = reservations.get(item.getReservationId());
            if (r == null || reservationRepository.transition(r.getId(), ReservationStatus.RESERVED,
                    ReservationStatus.EXPORTED, now) == 0) {
                throw SupplyException.conflict("RESERVATION_NOT_HELD",
                        "Hàng giữ cho đơn không còn, không thể xuất kho");
            }
            ledger.exportReserved(item.getProductId(), item.getQuantity(), shipment.getOrderId(),
                    shipment.getId(), item.isGift(), actorId);
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    static String label(ShipmentStatus s) {
        return switch (s) {
            case PREPARING -> "Chuẩn bị";
            case SHIPPING -> "Đang giao";
            case DELIVERED -> "Giao thành công";
            case FAILED -> "Giao thất bại";
            case RETURNED -> "Đã về kho";
            case CANCELLED -> "Đã hủy";
        };
    }
}
