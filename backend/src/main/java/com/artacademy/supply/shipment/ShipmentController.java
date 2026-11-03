package com.artacademy.supply.shipment;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.artacademy.dto.ApiResponse;
import com.artacademy.supply.common.SupplyCurrentUser;
import com.artacademy.supply.shipment.ShipmentDtos.ShipmentResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

/** Học viên xem đơn giao học cụ/quà của mình. Cần đăng nhập (SecurityConfig: anyRequest().authenticated()). */
@RestController
@RequestMapping("/api/shipments")
@Tag(name = "Supplies - Shipments", description = "Học viên xem giao hàng học cụ/quà tặng (E)")
public class ShipmentController {

    private final ShipmentService shipmentService;
    private final SupplyCurrentUser currentUser;

    public ShipmentController(ShipmentService shipmentService, SupplyCurrentUser currentUser) {
        this.shipmentService = shipmentService;
        this.currentUser = currentUser;
    }

    @GetMapping("/me")
    @Operation(summary = "Danh sách đơn giao của tôi")
    public ApiResponse<List<ShipmentResponse>> mine() {
        return ApiResponse.success("OK", shipmentService.listMine(currentUser.require()));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết đơn giao (chủ đơn, STAFF hoặc ADMIN)")
    public ApiResponse<ShipmentResponse> get(@PathVariable Long id) {
        return ApiResponse.success("OK", shipmentService.getForUser(id, currentUser.require()));
    }

    @GetMapping("/by-order/{orderId}")
    @Operation(summary = "Trạng thái giao của một đơn hàng (dùng ở màn chi tiết đơn của D)")
    public ApiResponse<ShipmentResponse> byOrder(@PathVariable Integer orderId) {
        return ApiResponse.success("OK", shipmentService.getByOrder(orderId, currentUser.require()));
    }
}
