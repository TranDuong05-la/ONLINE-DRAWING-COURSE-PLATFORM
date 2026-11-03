package com.drawingcourse.backend.entity;

import com.drawingcourse.backend.service.OrderPersistenceMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.List;
import java.util.Optional;
import static org.junit.jupiter.api.Assertions.*;

class OrderDeadlineTest {
    private Order create(Clock clock) {
        return Order.create(10, List.of(new OrderLineSnapshot(OrderItemType.PRODUCT, 1, "Brush", 3, 10, true)),
                0, Optional.of(new OrderVoucher("ONE", OrderVoucher.Kind.FIXED_VND, 1, 1)), clock);
    }

    @Test void fixedClockSetsExactlyThirtyMinutesAtDatabasePrecision() {
        Instant now = Instant.parse("2026-10-23T23:50:00.123456789Z");
        var order = create(Clock.fixed(now, ZoneId.of("Asia/Ho_Chi_Minh")));
        assertEquals(Instant.parse("2026-10-23T23:50:00.123456Z"), order.getCreatedAt());
        assertEquals(order.getCreatedAt().plus(Duration.ofMinutes(30)), order.getExpiresAt());
        assertEquals(Instant.parse("2026-10-24T00:20:00.123456Z"), order.getExpiresAt());
    }

    @Test void deadlineAndMonetarySnapshotsSurviveMappingAndFlushCallback() {
        var order = create(Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC));
        var mapper = new OrderPersistenceMapper();
        var row = mapper.toNewEntity(order);
        ReflectionTestUtils.invokeMethod(row, "initializeTimestamps");
        var restored = mapper.toDomain(row);
        assertEquals(order.getCreatedAt(), restored.getCreatedAt());
        assertEquals(order.getExpiresAt(), restored.getExpiresAt());
        assertEquals(order.getItems(), restored.getItems());
        assertEquals(29, restored.getTotalVnd());
    }

    @Test void paymentFailureAndRetryStatusesDoNotMoveDeadline() {
        var order = create(Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC));
        var mapper = new OrderPersistenceMapper();
        var row = mapper.toNewEntity(order);
        // Simulate future payment-state persistence, not a payment retry implementation.
        for (var status : List.of(PaymentStatus.FAILED, PaymentStatus.PENDING, PaymentStatus.FAILED)) {
            ReflectionTestUtils.setField(row, "paymentStatus", status);
            ReflectionTestUtils.invokeMethod(row, "updateTimestamp");
            var restored = mapper.toDomain(row);
            assertEquals(order.getCreatedAt(), restored.getCreatedAt());
            assertEquals(order.getExpiresAt(), restored.getExpiresAt());
            assertEquals(order.getItems(), restored.getItems());
        }
    }

    @Test void candidateBoundaryIncludesDeadlineButNotPaidOrExpiredOrders() {
        var order = create(Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC));
        assertFalse(order.isEligibleForExpiration(order.getExpiresAt().minusNanos(1)));
        assertTrue(order.isEligibleForExpiration(order.getExpiresAt()));
        assertTrue(order.isEligibleForExpiration(order.getExpiresAt().plusSeconds(1)));
        var mapper = new OrderPersistenceMapper();
        var row = mapper.toNewEntity(order);
        ReflectionTestUtils.setField(row, "paymentStatus", PaymentStatus.SUCCEEDED);
        assertFalse(mapper.toDomain(row).isEligibleForExpiration(order.getExpiresAt()));
        ReflectionTestUtils.setField(row, "paymentStatus", PaymentStatus.UNKNOWN);
        assertFalse(mapper.toDomain(row).isEligibleForExpiration(order.getExpiresAt()));
        ReflectionTestUtils.setField(row, "paymentStatus", PaymentStatus.CREATED);
        ReflectionTestUtils.setField(row, "status", OrderStatus.EXPIRED);
        assertFalse(mapper.toDomain(row).isEligibleForExpiration(order.getExpiresAt()));
    }

    @Test void converterRoundTripsUtcWithoutDefaultTimezoneAndRejectsPrecisionLoss() {
        var converter = new OrderUtcInstantConverter();
        var instant = Instant.parse("2026-10-23T23:59:59.123456Z");
        assertEquals(LocalDateTime.parse("2026-10-23T23:59:59.123456"), converter.convertToDatabaseColumn(instant));
        assertEquals(instant, converter.convertToEntityAttribute(converter.convertToDatabaseColumn(instant)));
        assertNull(converter.convertToEntityAttribute(null));
        assertNull(converter.convertToDatabaseColumn(null));
        assertThrows(IllegalArgumentException.class, () -> converter.convertToDatabaseColumn(instant.plusNanos(1)));
    }

    @Test void legacyMissingDeadlineIsNotInventedOnRestore() {
        var order = create(Clock.fixed(Instant.parse("2026-10-23T00:00:00Z"), ZoneOffset.UTC));
        var mapper = new OrderPersistenceMapper();
        var row = mapper.toNewEntity(order);
        ReflectionTestUtils.setField(row, "paymentExpiresAt", null);
        var restored = mapper.toDomain(row);
        assertNull(restored.getExpiresAt());
        assertFalse(restored.isEligibleForExpiration(Instant.MAX));
        ReflectionTestUtils.setField(row, "paymentExpiresAt", order.getCreatedAt());
        assertThrows(IllegalArgumentException.class, () -> mapper.toDomain(row));
    }
}
