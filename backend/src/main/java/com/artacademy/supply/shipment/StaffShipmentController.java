package com.artacademy.supply.shipment;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.artacademy.dto.ApiResponse;
import com.artacademy.supply.common.PageResult;
import com.artacademy.supply.common.SupplyCurrentUser;
import com.artacademy.supply.shipment.ShipmentDtos.ShipmentResponse;
import com.artacademy.supply.shipment.ShipmentDtos.ShipmentSummary;
import com.artacademy.supply.shipment.ShipmentDtos.UpdateShipmentStatusRequest;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/** Đóng gói và điều phối giao hàng. STAFF hoặc ADMIN (SecurityConfig: /api/staff/**). */
@RestController
@RequestMapping("/api/staff/shipments")
@Tag(name = "Staff - Shipments", description = "Đóng gói, xuất kho, cập nhật giao hàng (E)")
public class StaffShipmentController {

    private final ShipmentService shipmentService;
    private final SupplyCurrentUser currentUser;

    public StaffShipmentController(ShipmentService shipmentService, SupplyCurrentUser currentUser) {
        this.shipmentService = shipmentService;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "Danh sách giao hàng theo trạng thái (?status=PREPARING&status=FAILED)")
    public ApiResponse<PageResult<ShipmentResponse>> list(
            @RequestParam(required = false) List<ShipmentStatus> status,
            @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success("OK", shipmentService.listForManager(status, page, size));
    }

    @GetMapping("/summary")
    @Operation(summary = "Số đơn theo từng trạng thái")
    public ApiResponse<ShipmentSummary> summary() {
        return ApiResponse.success("OK", shipmentService.summary());
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết đơn giao")
    public ApiResponse<ShipmentResponse> get(@PathVariable Long id) {
        return ApiResponse.success("OK", shipmentService.getForUser(id, currentUser.require()));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Đổi trạng thái giao (xuất kho khi chuyển sang Đang giao, nhập lại khi Hàng về kho)")
    public ApiResponse<ShipmentResponse> updateStatus(@PathVariable Long id,
            @Valid @RequestBody UpdateShipmentStatusRequest req) {
        return ApiResponse.success("Đã cập nhật trạng thái giao",
                shipmentService.updateStatus(id, req, currentUser.require()));
    }
}
