package com.drawingcourse.backend.entity;

/** Persisted Refund state vocabulary. Kept separate from provider and C/E adapters. */
public final class RefundPersistenceTypes {
    private RefundPersistenceTypes() { }

    public enum ScopeType { STANDALONE_COURSE, PRODUCT_UNITS, WHOLE_COMBO }
    public enum ItemType { COURSE, PRODUCT, COMBO }
    public enum AmountReservationStatus { RESERVED, CONSUMED, RELEASED }
    public enum DecisionStatus { REQUESTED, APPROVED, REJECTED }
    public enum FinancialStatus { NOT_STARTED, READY, PROCESSING, UNKNOWN, SUCCEEDED, FAILED, REQUIRES_REVIEW }
    public enum CourseAccessStatus { NOT_APPLICABLE, UNCHANGED, ELIGIBILITY_CONFIRMED, REVOCATION_PENDING, REVOKED, REQUIRES_REVIEW }
    public enum PhysicalStatus { NOT_REQUIRED, PENDING, ADJUSTED, RELEASED, REQUIRES_REVIEW }
    public enum ShipmentHoldStatus { NOT_APPLICABLE, ACTIVE, RELEASED, REQUIRES_REVIEW }
    public enum UnitAllocationStatus { RESERVED, REFUNDED, RELEASED }
    public enum ProviderAttemptStatus { PENDING, PROCESSING, UNKNOWN, SUCCEEDED, DEFINITIVE_FAILURE, REQUIRES_REVIEW }
    public enum EventOutcome { APPLIED, ALREADY_APPLIED, REJECTED }
    public enum ReviewStatus { OPEN, RESOLVED, DISMISSED }
}
