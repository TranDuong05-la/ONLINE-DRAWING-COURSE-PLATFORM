package com.artacademy.supply.dev;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.artacademy.dto.ApiResponse;
import com.artacademy.supply.api.ReservationView;
import com.artacademy.supply.api.ReserveLine;
import com.artacademy.supply.api.ShippingInfo;
import com.artacademy.supply.api.SupplyFulfillmentApi;
import com.artacademy.supply.common.SupplyCurrentUser;
import com.artacademy.supply.common.SupplyUser;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * CHỈ ĐỂ TEST trước khi ghép đơn hàng của D: giả lập tạo đơn + thanh toán để thử luồng
 * kho -> giao -> đánh giá. Không có ở profile prod; tắt bằng app.supply.dev-orders.enabled=false.
 * Mã đơn giả nằm trong khoảng 1.000.000.000+ để không trùng đơn thật của D.
 */
@RestController
@Profile("!prod")
@ConditionalOnProperty(name = "app.supply.dev-orders.enabled", havingValue = "true", matchIfMissing = true)
@RequestMapping("/api/dev/supply")
@Tag(name = "Dev - Supplies", description = "Giả lập đơn hàng để test phần E (không có ở prod)")
public class DevSupplyOrderController {

    private final SupplyFulfillmentApi fulfillment;
    private final SupplyCurrentUser currentUser;

    public DevSupplyOrderController(SupplyFulfillmentApi fulfillment, SupplyCurrentUser currentUser) {
        this.fulfillment = fulfillment;
        this.currentUser = currentUser;
    }

    public record DevOrderRequest(Integer orderId, List<ReserveLine> lines, String recipientName, String phone,
            String address, Boolean pay) {
    }

    @PostMapping("/orders")
    @Operation(summary = "Giữ hàng và (mặc định) thanh toán thử, tạo chuyến giao")
    public ApiResponse<Map<String, Object>> createOrder(@RequestBody DevOrderRequest req) {
        SupplyUser user = currentUser.require();
        int orderId = req.orderId() != null ? req.orderId()
                : 1_000_000_000 + ThreadLocalRandom.current().nextInt(999_999_999);
        List<ReservationView> reserved = fulfillment.reserveForOrder(orderId, user.id(), req.lines());
        Optional<Long> shipmentId = Optional.empty();
        if (req.pay() == null || req.pay()) {
            shipmentId = fulfillment.onOrderPaid(orderId, user.id(),
                    new ShippingInfo(req.recipientName(), req.phone(), req.address()));
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("orderId", orderId);
        body.put("reservations", reserved);
        body.put("shipmentId", shipmentId.orElse(null));
        return ApiResponse.success("Đã đặt thử", body);
    }

    @PostMapping("/orders/{orderId}/release")
    @Operation(summary = "Hủy thử đơn: trả giữ hàng")
    public ApiResponse<Map<String, Object>> release(@PathVariable Integer orderId) {
        currentUser.require();
        return ApiResponse.success("Đã trả giữ",
                Map.of("released", fulfillment.releaseForOrder(orderId, "Hủy thử (dev)")));
    }
}
