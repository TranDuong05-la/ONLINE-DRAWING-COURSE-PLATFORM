package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.*;
import java.util.*;

public final class VoucherPersistenceMapper {
    public VoucherJpaEntity toNewDefinition(Voucher definition) { return VoucherJpaEntity.fromDefinition(definition); }
    public Voucher toDomain(VoucherJpaEntity row) {
        return new Voucher(row.getCode(), row.getKind(), row.getValue(), optional(row.getPercentageCapVnd()),
                row.isActive(), row.getValidFrom(), row.getValidUntil(), row.getMinimumSubtotalVnd(),
                optional(row.getTotalUsageLimit()), optional(row.getPerUserUsageLimit()));
    }
    public VoucherUsageSnapshot toUsage(VoucherJpaEntity voucher, Integer userId, Optional<VoucherUserUsageJpaEntity> user) {
        user.ifPresent(row -> {
            if (!Objects.equals(row.getVoucherId(), voucher.getId()) || !row.getUserId().equals(userId))
                throw new SecurityException("Foreign voucher usage row");
        });
        return new VoucherUsageSnapshot(voucher.getCode(), userId, voucher.getReservedCount(), voucher.getRedeemedCount(),
                user.map(VoucherUserUsageJpaEntity::getReservedCount).orElse(0L), user.map(VoucherUserUsageJpaEntity::getRedeemedCount).orElse(0L));
    }
    public VoucherClaimJpaEntity toNewClaimSnapshot(VoucherClaim claim) { return VoucherClaimJpaEntity.fromSnapshot(claim); }
    public VoucherClaim toDomain(VoucherClaimJpaEntity row) {
        return new VoucherClaim(row.getClaimKey(), row.getVoucherId(), row.getDefinitionVersion(), row.getUserId(),
                row.getOrderId(), new OrderVoucher(row.getCodeSnapshot(), row.getKindSnapshot(), row.getValueSnapshot(), row.getCapSnapshotVnd()),
                row.getEligibleSubtotalVnd(), row.getDiscountVnd(), row.getOrderCreatedAt(), row.getExpiresAt(), row.getReservedAt(),
                row.getState(), row.getTransitionedAt(), row.getPaymentReference(), row.getPaidAt(), row.getReleaseReason());
    }
    private OptionalLong optional(Long value) { return value == null ? OptionalLong.empty() : OptionalLong.of(value); }
}
