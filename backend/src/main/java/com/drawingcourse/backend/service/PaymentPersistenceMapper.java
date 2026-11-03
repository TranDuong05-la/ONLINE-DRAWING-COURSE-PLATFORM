package com.drawingcourse.backend.service;

import com.drawingcourse.backend.entity.*;

public final class PaymentPersistenceMapper {
    public PaymentAttemptJpaEntity toNewAttempt(PaymentAttempt value) { return PaymentAttemptJpaEntity.newSnapshot(value); }
    public PaymentAttempt toDomain(PaymentAttemptJpaEntity row) {
        return new PaymentAttempt(row.getId(),row.getOrderId(),row.getUserId(),row.getProvider(),row.getMerchantReference(),
                row.getExpectedAmountVnd(),row.getCurrency(),row.getOrderCreatedAt(),row.getExpiresAt(),row.getCreatedAt(),row.getUpdatedAt(),
                row.getStatus(),row.getTransactionReference(),row.getSucceededAt(),row.getReviewReason());
    }
    public PaymentEventJpaEntity toNewEvent(PaymentEventAudit value) { return PaymentEventJpaEntity.newSnapshot(value); }
    public PaymentEventAudit toDomain(PaymentEventJpaEntity row) {
        var event=new PaymentEvent(row.getProvider(),row.getEventReference(),row.getMerchantReference(),row.getTransactionReference(),
                row.getAmountVnd(),row.getCurrency(),row.getOutcome(),row.getOccurredAt(),row.getReceivedAt(),row.getPayloadSha256(),row.getVerificationReference());
        return new PaymentEventAudit(row.getId(),row.getAttemptId(),row.getOrderId(),row.getUserId(),event,row.getFingerprint(),
                row.getBeforeStatus(),row.getAfterStatus(),row.getDisposition(),row.getReason(),row.getObservedOrderStatus());
    }
}
