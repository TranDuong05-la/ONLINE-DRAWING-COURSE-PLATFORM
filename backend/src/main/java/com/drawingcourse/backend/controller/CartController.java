package com.drawingcourse.backend.controller;

import com.artacademy.dto.ApiResponse;
import com.drawingcourse.backend.dto.CartItemQuantityRequest;
import com.drawingcourse.backend.dto.CartItemRequest;
import com.drawingcourse.backend.dto.CartResponse;
import com.drawingcourse.backend.service.CartApplicationService;
import com.drawingcourse.backend.service.CartAvailabilityProjector;
import com.drawingcourse.backend.service.CartState;
import jakarta.validation.Valid;
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

/** Authenticated Cart API. Enable only after trusted catalog, ownership and stock adapters exist. */
@RestController
@RequestMapping("/api/cart")
@ConditionalOnProperty(name = "app.cart.api-enabled", havingValue = "true")
public final class CartController {
    private final CartApplicationService carts;
    private final CartAvailabilityProjector projector;

    public CartController(CartApplicationService carts, CartAvailabilityProjector projector) {
        this.carts = Objects.requireNonNull(carts);
        this.projector = Objects.requireNonNull(projector);
    }

    @GetMapping
    public ResponseEntity<ApiResponse<CartResponse>> getCurrentCart() {
        return ok("Cart retrieved successfully", carts.load());
    }

    @PostMapping("/items")
    public ResponseEntity<ApiResponse<CartResponse>> addItem(@Valid @RequestBody CartItemRequest request) {
        return ok("Cart item added successfully",
                carts.add(request.itemType(), request.itemId(), request.quantity()));
    }

    @PatchMapping("/items/{cartItemId}")
    public ResponseEntity<ApiResponse<CartResponse>> updateQuantity(
            @PathVariable Integer cartItemId, @Valid @RequestBody CartItemQuantityRequest request) {
        requirePositiveId(cartItemId);
        return ok("Cart item updated successfully", carts.update(cartItemId, request.quantity()));
    }

    @DeleteMapping("/items/{cartItemId}")
    public ResponseEntity<ApiResponse<CartResponse>> removeItem(@PathVariable Integer cartItemId) {
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
