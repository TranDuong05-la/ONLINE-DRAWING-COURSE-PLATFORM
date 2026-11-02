package com.drawingcourse.backend.entity;

/** Durable settlement work lifecycle vocabulary; transitions belong to the future worker. */
public enum PaymentSettlementWorkState {
    READY, RETRY_WAIT, LEASED, COMPLETED, MANUAL_REVIEW, CLOSED_NOT_ELIGIBLE
}
