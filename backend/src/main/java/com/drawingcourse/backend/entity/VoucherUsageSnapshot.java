package com.drawingcourse.backend.entity;

import java.util.Objects;

/** Trusted usage projection: reserved excludes released claims; redeemed excludes those same reservations. */
public record VoucherUsageSnapshot(String voucherCode, Integer userId, long totalReserved, long totalRedeemed,
                                   long userReserved, long userRedeemed) {
    public VoucherUsageSnapshot {
        Objects.requireNonNull(voucherCode, "voucherCode");
        Objects.requireNonNull(userId, "userId");
        if (voucherCode.isBlank() || totalReserved < 0 || totalRedeemed < 0 || userReserved < 0 || userRedeemed < 0
                || userReserved > totalReserved || userRedeemed > totalRedeemed) {
            throw new IllegalArgumentException("Invalid voucher usage snapshot");
        }
        Math.addExact(totalReserved, totalRedeemed);
        Math.addExact(userReserved, userRedeemed);
    }
    public long totalCommittedUsage() { return Math.addExact(totalReserved, totalRedeemed); }
    public long userCommittedUsage() { return Math.addExact(userReserved, userRedeemed); }
}
