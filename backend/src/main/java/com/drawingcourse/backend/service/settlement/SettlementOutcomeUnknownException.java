package com.drawingcourse.backend.service.settlement;

/** Signals that a fresh database read could not resolve whether a settlement commit completed. */
public final class SettlementOutcomeUnknownException extends RuntimeException {
    public SettlementOutcomeUnknownException(String message, Throwable cause) { super(message, cause); }
}
