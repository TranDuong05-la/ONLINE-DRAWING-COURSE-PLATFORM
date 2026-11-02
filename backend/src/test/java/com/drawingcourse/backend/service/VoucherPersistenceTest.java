package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class VoucherPersistenceTest {
    private final Instant now = Instant.parse("2026-10-23T12:00:00Z");
    private final VoucherPersistenceMapper mapper = new VoucherPersistenceMapper();
    private Voucher voucher(OrderVoucher.Kind kind, long value, OptionalLong cap) {
        return new Voucher("DRAW", kind, value, cap, true, now.minusSeconds(10), now.plusSeconds(10),
                1, OptionalLong.of(50), OptionalLong.of(2));
    }
    private VoucherClaim claim() {
        var order = Order.create(10, List.of(new OrderLineSnapshot(OrderItemType.PRODUCT, 1, "Brush", 3, 10, true)),
                5, Optional.of(voucher(OrderVoucher.Kind.FIXED_VND, 1, OptionalLong.empty()).toOrderSnapshot()),
                Clock.fixed(now, ZoneOffset.UTC));
        return VoucherClaim.reserved("checkout-v1-test:voucher", 1, 4, 20, order, now);
    }

    @Test void definitionRoundTripsExactRulesAndOptionalLimits() {
        for (var definition : List.of(voucher(OrderVoucher.Kind.FIXED_VND, Long.MAX_VALUE, OptionalLong.empty()),
                voucher(OrderVoucher.Kind.PERCENT_BASIS_POINTS, 3333, OptionalLong.of(100)),
                voucher(OrderVoucher.Kind.PERCENT_BASIS_POINTS, 10000, OptionalLong.empty()),
                new Voucher("ZERO", OrderVoucher.Kind.PERCENT_BASIS_POINTS, 0, OptionalLong.of(0), false,
                        now, now.plusSeconds(1),0,OptionalLong.empty(),OptionalLong.of(0)))) {
            assertEquals(definition, mapper.toDomain(mapper.toNewDefinition(definition)));
        }
    }

    @Test void definitionEditKeepsIdentityVersionAndCountersAndCannotChangeCode() {
        var definition = voucher(OrderVoucher.Kind.FIXED_VND, 1, OptionalLong.empty());
        var row = mapper.toNewDefinition(definition);
        ReflectionTestUtils.setField(row,"id",1); ReflectionTestUtils.setField(row,"version",4L);
        ReflectionTestUtils.setField(row,"reservedCount",3L); ReflectionTestUtils.setField(row,"redeemedCount",5L);
        row.replaceDefinition(definition.withActive(false));
        assertFalse(mapper.toDomain(row).active());
        assertEquals(1,row.getId()); assertEquals(4L,row.getVersion());
        assertEquals(3,row.getReservedCount()); assertEquals(5,row.getRedeemedCount());
        var other = new Voucher("OTHER",definition.kind(),1,OptionalLong.empty(),true,definition.validFrom(),definition.validUntil(),
                0,OptionalLong.empty(),OptionalLong.empty());
        assertThrows(IllegalArgumentException.class, () -> row.replaceDefinition(other));
    }

    @Test void globalAndUserCountersMapReservedPlusRedeemedAndRejectForeignScope() {
        var row = mapper.toNewDefinition(voucher(OrderVoucher.Kind.FIXED_VND,1,OptionalLong.empty()));
        ReflectionTestUtils.setField(row,"id",1);
        ReflectionTestUtils.setField(row,"reservedCount",3L); ReflectionTestUtils.setField(row,"redeemedCount",5L);
        var user = new VoucherUserUsageJpaEntity(1,10);
        ReflectionTestUtils.setField(user,"reservedCount",1L); ReflectionTestUtils.setField(user,"redeemedCount",1L);
        var usage = mapper.toUsage(row,10,Optional.of(user));
        assertEquals(8,usage.totalCommittedUsage()); assertEquals(2,usage.userCommittedUsage());
        assertEquals(0,mapper.toUsage(row,10,Optional.empty()).userCommittedUsage());
        assertThrows(SecurityException.class, () -> mapper.toUsage(row,11,Optional.of(user)));
        assertThrows(SecurityException.class, () -> mapper.toUsage(row,10,Optional.of(new VoucherUserUsageJpaEntity(2,10))));
        ReflectionTestUtils.setField(row,"reservedCount",Long.MAX_VALUE);
        assertThrows(ArithmeticException.class, () -> mapper.toUsage(row,10,Optional.empty()));
    }

    @Test void reservedRedeemedReleasedClaimsRoundTripWithoutCurrentDefinition() {
        var reserved = claim();
        var redeemed = reserved.redeem("payment-1",now.plusSeconds(50),now.plusSeconds(51));
        var released = reserved.releaseUnpaid(VoucherClaim.ReleaseReason.EXPIRED,reserved.expiresAt());
        for (var snapshot : List.of(reserved,redeemed,released)) {
            var row = mapper.toNewClaimSnapshot(snapshot);
            ReflectionTestUtils.setField(row,"id",30); ReflectionTestUtils.setField(row,"version",2L);
            assertEquals(snapshot,mapper.toDomain(row));
            assertEquals(30,row.getId()); assertEquals(2L,row.getVersion());
            assertEquals(1,mapper.toDomain(row).discountVnd());
        }
    }

    @Test void campaignEndAndDeactivationDoNotInvalidateProtectedReservation() {
        var definition = voucher(OrderVoucher.Kind.FIXED_VND,1,OptionalLong.empty());
        var claim = claim();
        assertTrue(definition.validUntil().isBefore(claim.expiresAt()));
        var row = mapper.toNewDefinition(definition);
        row.replaceDefinition(definition.withActive(false));
        var redeemed = claim.redeem("payment-1",now.plusSeconds(600),now.plusSeconds(601));
        assertEquals(VoucherClaim.State.REDEEMED,redeemed.state());
        assertEquals(claim.terms(),redeemed.terms());
        assertEquals(claim.expiresAt(),redeemed.expiresAt());
        assertFalse(mapper.toDomain(row).active());
    }

    @Test void idempotentTerminalTransitionsNeverConsumeTwiceOrSwitchTerminalState() {
        var reserved = claim();
        var redeemed = reserved.redeem("payment-1",now.plusSeconds(10),now.plusSeconds(11));
        assertSame(redeemed,redeemed.redeem("payment-1",now.plusSeconds(10),now.plusSeconds(5000)));
        assertThrows(IllegalStateException.class, () -> redeemed.redeem("other",now.plusSeconds(10),now.plusSeconds(11)));
        assertThrows(IllegalStateException.class, () -> redeemed.releaseUnpaid(VoucherClaim.ReleaseReason.CANCELLED,now.plusSeconds(12)));
        var released = reserved.releaseUnpaid(VoucherClaim.ReleaseReason.CANCELLED,now.plusSeconds(5));
        assertSame(released,released.releaseUnpaid(VoucherClaim.ReleaseReason.CANCELLED,now.plusSeconds(10)));
        assertThrows(IllegalStateException.class, () -> released.redeem("late",now.plusSeconds(10),now.plusSeconds(11)));
    }

    @Test void deadlineBoundaryAndLateCallbacksRequireReconciliation() {
        var reserved = claim();
        assertEquals(now.plusSeconds(1800),reserved.expiresAt());
        assertEquals(VoucherClaim.State.REDEEMED,reserved.redeem("on-time",reserved.expiresAt().minusSeconds(1),reserved.expiresAt().minusSeconds(1)).state());
        assertThrows(IllegalStateException.class, () -> reserved.redeem("late",reserved.expiresAt(),reserved.expiresAt()));
        assertThrows(IllegalStateException.class, () -> reserved.redeem("delayed",now.plusSeconds(10),reserved.expiresAt()));
        assertThrows(IllegalArgumentException.class, () -> reserved.releaseUnpaid(VoucherClaim.ReleaseReason.EXPIRED,reserved.expiresAt().minusSeconds(1)));
        assertEquals(VoucherClaim.State.RELEASED,reserved.releaseUnpaid(VoucherClaim.ReleaseReason.EXPIRED,reserved.expiresAt()).state());
        assertEquals(VoucherClaim.State.RESERVED,reserved.state()); // No attempt-failure mutator/release.
    }

    @Test void legacyMoneyIsNotRecomputedFromClaimTerms() {
        var original = claim();
        var row = mapper.toNewClaimSnapshot(original);
        ReflectionTestUtils.setField(row,"valueSnapshot",2L); ReflectionTestUtils.setField(row,"capSnapshotVnd",2L);
        // Stored discount stays 1 even when the stored rule would currently calculate 2.
        var restored = mapper.toDomain(row);
        assertEquals(2,restored.terms().discountFor(30));
        assertEquals(1,restored.discountVnd());
    }

    @Test void utcConversionPreservesPrecisionAndRejectsLoss() {
        var converter = new VoucherUtcInstantConverter();
        var time = Instant.parse("2026-10-23T12:00:00.123456Z");
        assertEquals(LocalDateTime.parse("2026-10-23T12:00:00.123456"),converter.convertToDatabaseColumn(time));
        assertEquals(time,converter.convertToEntityAttribute(converter.convertToDatabaseColumn(time)));
        assertNull(converter.convertToDatabaseColumn(null)); assertNull(converter.convertToEntityAttribute(null));
        assertThrows(IllegalArgumentException.class, () -> converter.convertToDatabaseColumn(time.plusNanos(1)));
    }
}
