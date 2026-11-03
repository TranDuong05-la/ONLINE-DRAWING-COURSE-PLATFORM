package com.drawingcourse.backend.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

import static com.drawingcourse.backend.entity.RefundPersistenceTypes.*;

@Entity
@Table(name = "refund_requests", uniqueConstraints = {
        @UniqueConstraint(name = "uk_refund_request_owner_key", columnNames = {"user_id", "idempotency_key"}),
        @UniqueConstraint(name = "uk_refund_request_id_item", columnNames = {"id", "order_item_id"})})
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class RefundRequestJpaEntity {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY) private Long id;
    @Version @Column(nullable = false) private Long version;
    @Column(name = "user_id", nullable = false, updatable = false) private Integer userId;
    @Column(name = "order_id", nullable = false, updatable = false) private Integer orderId;
    @Column(name = "order_item_id", nullable = false, updatable = false) private Integer orderItemId;
    @Column(name = "payment_settlement_id", nullable = false, updatable = false) private Integer paymentSettlementId;
    @Column(name = "idempotency_key", nullable = false, length = 128, updatable = false) private String idempotencyKey;
    @Column(name = "request_fingerprint", nullable = false, length = 64,
            columnDefinition = "CHAR(64)", updatable = false) private String requestFingerprint;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "scope_type", nullable = false, length = 32, updatable = false) private ScopeType scopeType;
    @Column(name = "reason_code", nullable = false, length = 64, updatable = false) private String reasonCode;
    @Column(name = "customer_note", columnDefinition = "TEXT", updatable = false) private String customerNote;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "item_type_snapshot", nullable = false, length = 16, updatable = false) private ItemType itemTypeSnapshot;
    @Column(name = "item_quantity_snapshot", nullable = false, updatable = false) private int itemQuantitySnapshot;
    @Column(name = "item_gross_amount_vnd", nullable = false, updatable = false) private long itemGrossAmountVnd;
    @Column(name = "item_discount_vnd", nullable = false, updatable = false) private long itemDiscountVnd;
    @Column(name = "item_net_amount_vnd", nullable = false, updatable = false) private long itemNetAmountVnd;
    @Column(name = "item_voucher_eligible", nullable = false, updatable = false) private boolean itemVoucherEligible;
    @Column(name = "requested_quantity", nullable = false, updatable = false) private int requestedQuantity;
    @Column(name = "requested_amount_vnd", nullable = false, updatable = false) private long requestedAmountVnd;
    @Column(nullable = false, length = 3, updatable = false) private String currency;
    @Column(name = "captured_amount_vnd_snapshot", nullable = false, updatable = false) private long capturedAmountVndSnapshot;
    @Convert(converter = PaymentUtcInstantConverter.class) @Column(name = "provider_paid_at", nullable = false, updatable = false) private Instant providerPaidAt;
    @Convert(converter = PaymentUtcInstantConverter.class) @Column(name = "settled_at", nullable = false, updatable = false) private Instant settledAt;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "amount_reservation_status", nullable = false, length = 16) private AmountReservationStatus amountReservationStatus;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "decision_status", nullable = false, length = 16) private DecisionStatus decisionStatus;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "financial_status", nullable = false, length = 24) private FinancialStatus financialStatus;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "course_access_status", nullable = false, length = 24) private CourseAccessStatus courseAccessStatus;
    @Column(name = "physical_effect_required", nullable = false) private boolean physicalEffectRequired;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "physical_status", nullable = false, length = 24) private PhysicalStatus physicalStatus;
    @Enumerated(EnumType.STRING) @JdbcTypeCode(SqlTypes.VARCHAR)
    @Column(name = "shipment_hold_status", nullable = false, length = 24) private ShipmentHoldStatus shipmentHoldStatus;
    @Column(name = "course_evidence_reference", length = 255) private String courseEvidenceReference;
    @Column(name = "course_evidence_version", length = 128) private String courseEvidenceVersion;
    @Convert(converter = PaymentUtcInstantConverter.class) @Column(name = "course_evidence_observed_at") private Instant courseEvidenceObservedAt;
    @Column(name = "course_revalidation_fence", length = 255) private String courseRevalidationFence;
    @Column(name = "shipment_reference", length = 255) private String shipmentReference;
    @Column(name = "shipment_hold_reference", length = 255) private String shipmentHoldReference;
    @Column(name = "product_evidence_reference", length = 255) private String productEvidenceReference;
    @Convert(converter = PaymentUtcInstantConverter.class) @Column(name = "product_evidence_observed_at") private Instant productEvidenceObservedAt;
    @Column(name = "decided_by_user_id") private Integer decidedByUserId;
    @Convert(converter = PaymentUtcInstantConverter.class) @Column(name = "requested_at", nullable = false, updatable = false) private Instant requestedAt;
    @Convert(converter = PaymentUtcInstantConverter.class) @Column(name = "decision_at") private Instant decisionAt;
    @Convert(converter = PaymentUtcInstantConverter.class) @Column(name = "financial_executed_at") private Instant financialExecutedAt;
    @Convert(converter = PaymentUtcInstantConverter.class) @Column(name = "created_at", nullable = false, updatable = false) private Instant createdAt;
    @Convert(converter = PaymentUtcInstantConverter.class) @Column(name = "updated_at", nullable = false) private Instant updatedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
            @JoinColumn(name = "order_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "user_id", referencedColumnName = "user_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_refund_request_owner_route"))
    private OrderJpaEntity orderReference;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
            @JoinColumn(name = "order_item_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "order_id", referencedColumnName = "order_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_refund_request_item_route"))
    private OrderItemJpaEntity itemReference;
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumns(value = {
            @JoinColumn(name = "payment_settlement_id", referencedColumnName = "id", insertable = false, updatable = false),
            @JoinColumn(name = "order_id", referencedColumnName = "order_id", insertable = false, updatable = false)
    }, foreignKey = @ForeignKey(name = "fk_refund_request_settlement_route"))
    private PaymentSettlementJpaEntity settlementReference;

    public static RefundRequestJpaEntity create(CreateSnapshot value) {
        Objects.requireNonNull(value, "value");
        if (value.requestedAmountVnd <= 0 || value.capturedAmountVnd <= 0)
            throw new IllegalArgumentException("Financial refund amounts must be positive");
        var row = new RefundRequestJpaEntity();
        row.userId = positive(value.userId, "userId"); row.orderId = positive(value.orderId, "orderId");
        row.orderItemId = positive(value.orderItemId, "orderItemId"); row.paymentSettlementId = positive(value.paymentSettlementId, "paymentSettlementId");
        row.idempotencyKey = bounded(value.idempotencyKey, 128, "idempotencyKey"); row.requestFingerprint = sha(value.requestFingerprint);
        row.scopeType = Objects.requireNonNull(value.scopeType); row.reasonCode = bounded(value.reasonCode, 64, "reasonCode"); row.customerNote = value.customerNote;
        row.itemTypeSnapshot = Objects.requireNonNull(value.itemType); row.itemQuantitySnapshot = value.itemQuantity;
        row.itemGrossAmountVnd = value.itemGrossAmountVnd; row.itemDiscountVnd = value.itemDiscountVnd; row.itemNetAmountVnd = value.itemNetAmountVnd;
        row.itemVoucherEligible = value.itemVoucherEligible; row.requestedQuantity = value.requestedQuantity; row.requestedAmountVnd = value.requestedAmountVnd;
        row.currency = bounded(value.currency, 3, "currency"); row.capturedAmountVndSnapshot = value.capturedAmountVnd;
        row.providerPaidAt = Objects.requireNonNull(value.providerPaidAt); row.settledAt = Objects.requireNonNull(value.settledAt);
        row.amountReservationStatus = AmountReservationStatus.RESERVED; row.decisionStatus = DecisionStatus.REQUESTED;
        row.financialStatus = FinancialStatus.NOT_STARTED;
        boolean course = value.itemType == ItemType.COURSE || value.itemType == ItemType.COMBO;
        row.courseAccessStatus = course ? CourseAccessStatus.UNCHANGED : CourseAccessStatus.NOT_APPLICABLE;
        row.physicalEffectRequired = value.physicalEffectRequired;
        row.physicalStatus = value.physicalEffectRequired ? PhysicalStatus.PENDING : PhysicalStatus.NOT_REQUIRED;
        row.shipmentHoldStatus = value.physicalEffectRequired ? ShipmentHoldStatus.ACTIVE : ShipmentHoldStatus.NOT_APPLICABLE;
        row.requestedAt = Objects.requireNonNull(value.requestedAt).truncatedTo(ChronoUnit.MICROS);
        row.createdAt = row.requestedAt; row.updatedAt = row.requestedAt;
        row.validateSnapshot();
        return row;
    }

    private void validateSnapshot() {
        if (itemQuantitySnapshot <= 0 || requestedQuantity <= 0 || requestedQuantity > itemQuantitySnapshot
                || itemGrossAmountVnd < 0 || itemDiscountVnd < 0 || itemNetAmountVnd < 0
                || itemGrossAmountVnd - itemDiscountVnd != itemNetAmountVnd
                || requestedAmountVnd > itemNetAmountVnd || requestedAmountVnd > capturedAmountVndSnapshot)
            throw new IllegalArgumentException("Invalid immutable Refund monetary snapshot");
        if (scopeType == ScopeType.PRODUCT_UNITS && itemTypeSnapshot != ItemType.PRODUCT)
            throw new IllegalArgumentException("Product scope requires Product snapshot");
        if (scopeType != ScopeType.PRODUCT_UNITS && (requestedQuantity != 1 || itemQuantitySnapshot != 1
                || requestedAmountVnd != itemNetAmountVnd))
            throw new IllegalArgumentException("Course and Combo refunds reserve the whole item");
        if (scopeType == ScopeType.WHOLE_COMBO && (itemTypeSnapshot != ItemType.COMBO || itemVoucherEligible
                || itemDiscountVnd != 0 || itemGrossAmountVnd != itemNetAmountVnd))
            throw new IllegalArgumentException("Combo refund snapshot is invalid");
    }

    public void markFinancialUnknown(Instant at) {
        if (financialStatus != FinancialStatus.PROCESSING) throw new IllegalStateException("Refund is not processing");
        financialStatus = FinancialStatus.UNKNOWN; updatedAt = Objects.requireNonNull(at).truncatedTo(ChronoUnit.MICROS);
    }

    public void markFinancialSucceeded(Instant at) {
        if (financialStatus != FinancialStatus.PROCESSING && financialStatus != FinancialStatus.UNKNOWN)
            throw new IllegalStateException("Refund is not awaiting provider evidence");
        financialStatus = FinancialStatus.SUCCEEDED; amountReservationStatus = AmountReservationStatus.CONSUMED;
        financialExecutedAt = Objects.requireNonNull(at).truncatedTo(ChronoUnit.MICROS); updatedAt = financialExecutedAt;
        if (courseAccessStatus != CourseAccessStatus.NOT_APPLICABLE) courseAccessStatus = CourseAccessStatus.REVOCATION_PENDING;
    }

    public void startFinancial(Instant at) {
        if (financialStatus != FinancialStatus.READY) throw new IllegalStateException("Refund is not ready");
        financialStatus = FinancialStatus.PROCESSING; updatedAt = Objects.requireNonNull(at).truncatedTo(ChronoUnit.MICROS);
    }

    public void approve(Integer adminId, Instant at) {
        if (decisionStatus != DecisionStatus.REQUESTED) throw new IllegalStateException("Refund is already decided");
        decisionStatus = DecisionStatus.APPROVED; decidedByUserId = positive(adminId, "adminId");
        decisionAt = Objects.requireNonNull(at).truncatedTo(ChronoUnit.MICROS); updatedAt = decisionAt;
        financialStatus = FinancialStatus.READY;
    }

    /** Reject a non-physical request; physical requests require the authoritative Member E HOLD release first. */
    public void reject(Integer adminId, Instant at) {
        if (decisionStatus != DecisionStatus.REQUESTED) throw new IllegalStateException("Refund is already decided");
        if (physicalEffectRequired)
            throw new IllegalStateException("Physical refund rejection requires authoritative Shipment HOLD release");
        if (financialStatus != FinancialStatus.NOT_STARTED || amountReservationStatus != AmountReservationStatus.RESERVED)
            throw new IllegalStateException("Refund cannot be rejected after financial processing starts");
        decisionStatus = DecisionStatus.REJECTED; decidedByUserId = positive(adminId, "adminId");
        decisionAt = Objects.requireNonNull(at).truncatedTo(ChronoUnit.MICROS); updatedAt = decisionAt;
        amountReservationStatus = AmountReservationStatus.RELEASED;
    }

    public record CreateSnapshot(Integer userId, Integer orderId, Integer orderItemId, Integer paymentSettlementId,
                                 String idempotencyKey, String requestFingerprint, ScopeType scopeType,
                                 String reasonCode, String customerNote, ItemType itemType, int itemQuantity,
                                 long itemGrossAmountVnd, long itemDiscountVnd, long itemNetAmountVnd,
                                 boolean itemVoucherEligible, int requestedQuantity, long requestedAmountVnd,
                                 String currency, long capturedAmountVnd, Instant providerPaidAt, Instant settledAt,
                                 boolean physicalEffectRequired, Instant requestedAt) { }

    private static Integer positive(Integer value, String name) { if (value == null || value <= 0) throw new IllegalArgumentException(name); return value; }
    private static String bounded(String value, int max, String name) { String v = Objects.requireNonNull(value, name).strip(); if (v.isEmpty() || v.length() > max) throw new IllegalArgumentException(name); return v; }
    private static String sha(String value) { String v = Objects.requireNonNull(value).toLowerCase(java.util.Locale.ROOT); if (!v.matches("[0-9a-f]{64}")) throw new IllegalArgumentException("fingerprint"); return v; }
}
