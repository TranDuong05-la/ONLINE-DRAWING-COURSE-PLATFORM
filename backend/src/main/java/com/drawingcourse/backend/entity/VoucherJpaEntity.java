package com.drawingcourse.backend.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import java.time.*;
import java.time.temporal.ChronoUnit;

@Entity @Table(name = "vouchers", uniqueConstraints = @UniqueConstraint(name = "uk_vouchers_code", columnNames = "code"))
@Getter @NoArgsConstructor(access = AccessLevel.PROTECTED)
public class VoucherJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Integer id;
    @Version @Column(nullable = false) private Long version;
    @Column(name = "definition_version", nullable = false) private long definitionVersion;
    @Column(nullable = false, updatable = false, length = 255) private String code;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR) @Column(nullable = false, length = 24)
    private OrderVoucher.Kind kind;
    @Column(name = "discount_value", nullable = false) private long value;
    @Column(name = "percentage_cap_vnd") private Long percentageCapVnd;
    @Column(nullable = false) private boolean active;
    @Convert(converter = VoucherUtcInstantConverter.class) @Column(name = "valid_from", nullable = false) private Instant validFrom;
    @Convert(converter = VoucherUtcInstantConverter.class) @Column(name = "valid_until", nullable = false) private Instant validUntil;
    @Column(name = "minimum_subtotal_vnd", nullable = false) private long minimumSubtotalVnd;
    @Column(name = "total_usage_limit") private Long totalUsageLimit;
    @Column(name = "per_user_usage_limit") private Long perUserUsageLimit;
    @Column(name = "reserved_count", nullable = false) private long reservedCount;
    @Column(name = "redeemed_count", nullable = false) private long redeemedCount;
    @Convert(converter = VoucherUtcInstantConverter.class) @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Convert(converter = VoucherUtcInstantConverter.class) @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    public static VoucherJpaEntity fromDefinition(Voucher voucher) {
        var row = new VoucherJpaEntity(); row.code = voucher.code(); row.applyDefinition(voucher); row.definitionVersion = 1; return row;
    }
    /** Internal admin persistence primitive. Versioned edit never touches reserved claim snapshots/counters. */
    public void replaceDefinition(Voucher voucher) {
        if (!code.equals(voucher.code())) throw new IllegalArgumentException("Voucher code identity cannot change");
        applyDefinition(voucher);
        definitionVersion = Math.addExact(definitionVersion, 1);
    }
    private void applyDefinition(Voucher voucher) {
        new VoucherUtcInstantConverter().convertToDatabaseColumn(voucher.validFrom());
        new VoucherUtcInstantConverter().convertToDatabaseColumn(voucher.validUntil());
        kind = voucher.kind(); value = voucher.value(); percentageCapVnd = voucher.percentageCapVnd().isPresent() ? voucher.percentageCapVnd().getAsLong() : null;
        active = voucher.active(); validFrom = voucher.validFrom(); validUntil = voucher.validUntil();
        minimumSubtotalVnd = voucher.minimumSubtotalVnd();
        totalUsageLimit = voucher.totalUsageLimit().isPresent() ? voucher.totalUsageLimit().getAsLong() : null;
        perUserUsageLimit = voucher.perUserUsageLimit().isPresent() ? voucher.perUserUsageLimit().getAsLong() : null;
    }
    /** Counter mutations are made only while this definition row is pessimistically locked. */
    public void reserveOne() { reservedCount = Math.addExact(reservedCount, 1); }
    public void redeemOne() {
        if (reservedCount < 1) throw new IllegalStateException("Voucher reserved counter underflow");
        reservedCount = Math.subtractExact(reservedCount, 1);
        redeemedCount = Math.addExact(redeemedCount, 1);
    }
    public void releaseOne() {
        if (reservedCount < 1) throw new IllegalStateException("Voucher reserved counter underflow");
        reservedCount = Math.subtractExact(reservedCount, 1);
    }
    @PrePersist private void createTimestamps() { createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS); updatedAt = createdAt; }
    @PreUpdate private void updateTimestamp() { updatedAt = Instant.now().truncatedTo(ChronoUnit.MICROS); }
}
