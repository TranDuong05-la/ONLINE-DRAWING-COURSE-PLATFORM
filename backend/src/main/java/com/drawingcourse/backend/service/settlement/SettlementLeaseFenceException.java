package com.drawingcourse.backend.service.settlement;

/** Raised to roll back settlement effects when the final database-clock lease fence loses ownership. */
public final class SettlementLeaseFenceException extends RuntimeException {
    public SettlementLeaseFenceException(String message) { super(message); }
}
