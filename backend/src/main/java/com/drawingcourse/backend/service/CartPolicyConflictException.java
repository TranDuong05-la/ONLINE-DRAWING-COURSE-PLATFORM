package com.drawingcourse.backend.service;

/** A trusted Course or Cart policy currently rejects the requested Cart mutation. */
public final class CartPolicyConflictException extends RuntimeException {
    public CartPolicyConflictException(String message) { super(message); }
}
