package com.artacademy.supply.shipment;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public final class ShipmentDtos {
    private ShipmentDtos() {
    }

    public record ShipmentItemResponse(Long productId, String sku, String productName, String thumbnailUrl,
            int quantity, boolean gift) {
        static ShipmentItemResponse of(ShipmentItem i) {
            return new ShipmentItemResponse(i.getProductId(), i.getSku(), i.getProductName(), i.getThumbnailUrl(),
                    i.getQuantity(), i.isGift());
        }
    }

    /** nextStatuses chỉ có dữ liệu khi người xem là STAFF/ADMIN. */
    public record ShipmentResponse(Long id, Integer orderId, Integer userId, String recipientName,
            String recipientPhone, String shippingAddress, ShipmentStatus status, List<ShipmentStatus> nextStatuses,
            String carrier, String trackingCode, String note, int attempts, LocalDateTime createdAt,
            LocalDateTime shippedAt, LocalDateTime deliveredAt, LocalDateTime failedAt, LocalDateTime returnedAt,
            LocalDateTime cancelledAt, LocalDateTime updatedAt, int totalQuantity,
            List<ShipmentItemResponse> items) {

        static ShipmentResponse of(Shipment s, boolean forManager) {
            List<ShipmentItemResponse> items = s.getItems().stream().map(ShipmentItemResponse::of).toList();
            return new ShipmentResponse(s.getId(), s.getOrderId(), s.getUserId(), s.getRecipientName(),
                    s.getRecipientPhone(), s.getShippingAddress(), s.getStatus(),
                    forManager ? List.copyOf(s.getStatus().nextByAdmin()) : List.of(),
                    s.getCarrier(), s.getTrackingCode(), s.getNote(), s.getAttempts(), s.getCreatedAt(),
                    s.getShippedAt(), s.getDeliveredAt(), s.getFailedAt(), s.getReturnedAt(), s.getCancelledAt(),
                    s.getUpdatedAt(), items.stream().mapToInt(ShipmentItemResponse::quantity).sum(), items);
        }
    }

    public record UpdateShipmentStatusRequest(
            @NotNull(message = "Chọn trạng thái mới") ShipmentStatus status,
            @Size(max = 100) String carrier,
            @Size(max = 100) String trackingCode,
            @Size(max = 500) String note) {
    }

    public record ShipmentSummary(long preparing, long shipping, long delivered, long failed, long returned,
            long cancelled) {
    }
}
