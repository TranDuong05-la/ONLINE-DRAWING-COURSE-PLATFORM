package com.drawingcourse.backend.service;

/** The active Cart API does not support an item type already present in the persisted Cart. */
public final class CartItemTypeNotSupportedException extends RuntimeException {
    public CartItemTypeNotSupportedException(String message) { super(message); }
}
