package com.drawingcourse.backend.controller;

import com.artacademy.dto.ApiResponse;
import com.artacademy.payment.vnpay.PaymentSession;
import com.artacademy.payment.vnpay.PaymentSessionApplicationService;
import com.artacademy.security.AuthenticatedUserIdResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.InetAddress;
import java.time.Clock;
import java.time.Instant;
import java.util.NoSuchElementException;
import java.util.Objects;

/** Authenticated HTTP boundary for creating/reusing a local VNPay session; it never settles an Order. */
@RestController
@RequestMapping("/api/orders")
@ConditionalOnProperty(prefix = "app.payment.vnpay", name = {"enabled", "session-api.enabled"}, havingValue = "true")
public final class PaymentSessionController {
    private final PaymentSessionApplicationService sessions;
    private final AuthenticatedUserIdResolver authenticatedUser;
    private final Clock clock;

    public PaymentSessionController(PaymentSessionApplicationService sessions,
                                    AuthenticatedUserIdResolver authenticatedUser, Clock clock) {
        this.sessions = Objects.requireNonNull(sessions);
        this.authenticatedUser = Objects.requireNonNull(authenticatedUser);
        this.clock = Objects.requireNonNull(clock);
    }

    @PostMapping("/{orderId}/payment-session")
    public ResponseEntity<ApiResponse<PaymentSessionResponse>> createOrReuse(
            @PathVariable Integer orderId, HttpServletRequest request) {
        Integer userId = authenticatedUser.requireUserId();
        String clientIp = validatedPeerAddress(request.getRemoteAddr());
        Instant requestTime = clock.instant();
        PaymentSession result = sessions.createOrReuseForCourseOnlyOrder(userId, orderId, requestTime, clientIp);
        var body = new PaymentSessionResponse(result.orderId(), result.paymentUrl(), result.expiresAt(), result.reused());
        return ResponseEntity.ok(ApiResponse.success("Payment session is ready", body));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<ApiResponse<Void>> notFound() {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(ApiResponse.error("Order not found"));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> conflict() {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(ApiResponse.error("Order cannot create a payment session in its current state"));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ApiResponse<Void>> invalidRequest() {
        return ResponseEntity.badRequest().body(ApiResponse.error("Invalid payment session request"));
    }

    /** Uses only the socket peer address. Forwarding headers are deliberately ignored without a trusted-proxy policy. */
    static String validatedPeerAddress(String address) {
        if (address == null || address.isBlank() || address.length() > 45
                || !(address.matches("[0-9.]+") || address.matches("[0-9a-fA-F:.]+") && address.contains(":"))) {
            throw new IllegalArgumentException("Invalid peer address");
        }
        if (address.indexOf(':') < 0) {
            String[] octets = address.split("\\.", -1);
            if (octets.length != 4) throw new IllegalArgumentException("Invalid peer address");
            for (String octet : octets) {
                if (octet.isEmpty() || octet.length() > 3 || Integer.parseInt(octet) > 255)
                    throw new IllegalArgumentException("Invalid peer address");
            }
            return address;
        }
        try {
            InetAddress parsed = InetAddress.getByName(address);
            if (address.indexOf(':') >= 0 && !(parsed instanceof java.net.Inet6Address))
                throw new IllegalArgumentException("Invalid peer address");
            return parsed.getHostAddress();
        } catch (java.net.UnknownHostException ex) {
            throw new IllegalArgumentException("Invalid peer address", ex);
        }
    }

    /** Deliberately omits attempt IDs, merchant references, and raw provider evidence. */
    public record PaymentSessionResponse(Integer orderId, String paymentUrl, Instant expiresAt, boolean reused) { }
}
