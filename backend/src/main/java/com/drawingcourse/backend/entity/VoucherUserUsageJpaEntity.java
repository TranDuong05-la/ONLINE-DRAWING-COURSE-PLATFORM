package com.drawingcourse.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import java.util.Objects;

@Entity @Table(name = "voucher_user_usage", uniqueConstraints = @UniqueConstraint(
        name = "uk_voucher_user_usage", columnNames = {"voucher_id", "user_id"}))
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VoucherUserUsageJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Integer id;
    @Version @Column(nullable = false) private Long version;
    @Column(name = "voucher_id", nullable = false, updatable = false) private Integer voucherId;
    @Column(name = "user_id", nullable = false, updatable = false) private Integer userId;
    @Column(name = "reserved_count", nullable = false) private long reservedCount;
    @Column(name = "redeemed_count", nullable = false) private long redeemedCount;
    public VoucherUserUsageJpaEntity(Integer voucherId, Integer userId) {
        this.voucherId = Objects.requireNonNull(voucherId); this.userId = Objects.requireNonNull(userId);
    }
    public void reserveOne() { reservedCount = Math.addExact(reservedCount, 1); }
    public void redeemOne() {
        if (reservedCount < 1) throw new IllegalStateException("User voucher reserved counter underflow");
        reservedCount = Math.subtractExact(reservedCount, 1);
        redeemedCount = Math.addExact(redeemedCount, 1);
    }
    public void releaseOne() {
        if (reservedCount < 1) throw new IllegalStateException("User voucher reserved counter underflow");
        reservedCount = Math.subtractExact(reservedCount, 1);
    }
}
