package com.drawingcourse.backend.controller;

import com.artacademy.dto.ApiResponse;
import com.drawingcourse.backend.dto.CartItemQuantityRequest;
import com.drawingcourse.backend.dto.CartItemRequest;
import com.drawingcourse.backend.dto.CartResponse;
import com.drawingcourse.backend.entity.CartItemType;
import com.drawingcourse.backend.service.CartApplicationService;
import com.drawingcourse.backend.service.CartPolicyConflictException;
import com.drawingcourse.backend.service.CartState;
import com.drawingcourse.backend.service.CourseOnlyCartProjector;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.validation.Valid;

/** Authenticated Course-only view over the existing persisted Cart; Checkout is a separate disabled flow. */
@RestController
@RequestMapping("/api/cart")
@ConditionalOnProperty(name = "app.cart.course-only.api-enabled", havingValue = "true")
public final class CourseOnlyCartController {
    private final CartApplicationService carts;
    private final CourseOnlyCartProjector projector;

    public CourseOnlyCartController(CartApplicationService carts, CourseOnlyCartProjector projector) {
        this.carts = Objects.requireNonNull(carts);
        this.projector = Objects.requireNonNull(projector);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<CartResponse>> getCurrentCart() {
        return ok("Cart retrieved successfully", carts.load());
    }

    @PostMapping("/items")
    public ResponseEntity<ApiResponse<CartResponse>> addCourse(@Valid @RequestBody CartItemRequest request) {
        // A Combo is one purchasable line (quantity 1), like a Course; Products belong to the general Cart.
        if (request.itemType() != CartItemType.COURSE && request.itemType() != CartItemType.COMBO)
            throw new CartPolicyConflictException("Course-only Cart accepts Course and Combo items only");
        return ok("Cart item added successfully", carts.add(request.itemType(), request.itemId(), request.quantity()));
    }

    @PatchMapping("/items/{cartItemId}")
    public ResponseEntity<ApiResponse<CartResponse>> updateCourseQuantity(@PathVariable Integer cartItemId,
            @Valid @RequestBody CartItemQuantityRequest request) {
        requirePositiveId(cartItemId);
        if (request.quantity() != 1) throw new CartInvalidRequestException("Course and Combo quantity must be one");
        return ok("Cart item updated successfully", carts.update(cartItemId, 1));
    }

    @DeleteMapping("/items/{cartItemId}")
    public ResponseEntity<ApiResponse<CartResponse>> removeCourse(@PathVariable Integer cartItemId) {
        requirePositiveId(cartItemId);
        return ok("Cart item removed successfully", carts.remove(cartItemId));
    }

    @DeleteMapping
    public ResponseEntity<ApiResponse<CartResponse>> clearCart() {
        return ok("Cart cleared successfully", carts.clear());
    }

    private ResponseEntity<ApiResponse<CartResponse>> ok(String message, CartState state) {
        return ResponseEntity.ok(ApiResponse.success(message, projector.project(state)));
    }

    private static void requirePositiveId(Integer id) {
        if (id == null || id <= 0) throw new CartInvalidRequestException("Cart item ID must be positive");
    }
}
