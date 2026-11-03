package com.drawingcourse.backend.controller;

import com.artacademy.dto.ApiResponse;
import com.drawingcourse.backend.dto.CourseOnlyCheckoutHttpRequest;
import com.drawingcourse.backend.service.checkout.CourseOnlyCheckoutApplicationService;
import com.drawingcourse.backend.service.checkout.CourseCatalogPort;
import com.drawingcourse.backend.service.checkout.CoursePurchasePort;
import jakarta.validation.Valid;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Disabled by default; requires the real B/C integrations and explicit Course-only enablement. */
@RestController
@RequestMapping("/api/checkout/course-only")
@ConditionalOnProperty(name = "app.checkout.course-only.http-enabled", havingValue = "true")
@ConditionalOnBean({CourseCatalogPort.class, CoursePurchasePort.class, CourseOnlyCheckoutApplicationService.class})
public final class CourseOnlyCheckoutController {
    private final CourseOnlyCheckoutApplicationService checkout;

    public CourseOnlyCheckoutController(CourseOnlyCheckoutApplicationService checkout) {
        this.checkout = Objects.requireNonNull(checkout);
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CheckoutResponse>> checkout(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody CourseOnlyCheckoutHttpRequest request) {
        var result = checkout.checkout(idempotencyKey, request.toCheckoutRequest());
        var body = ApiResponse.success(result.replayed() ? "Checkout replayed" : "Order created",
                new CheckoutResponse(result.orderId(), result.replayed()));
        return ResponseEntity.status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED).body(body);
    }

    public record CheckoutResponse(Integer orderId, boolean replayed) { }
}
