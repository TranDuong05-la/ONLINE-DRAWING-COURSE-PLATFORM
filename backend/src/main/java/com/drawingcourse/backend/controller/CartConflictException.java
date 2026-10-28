package com.drawingcourse.backend.controller;

public final class CartConflictException extends RuntimeException {
    public CartConflictException(String message) { super(message); }
}
