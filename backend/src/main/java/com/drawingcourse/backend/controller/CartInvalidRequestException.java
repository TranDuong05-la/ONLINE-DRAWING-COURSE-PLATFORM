package com.drawingcourse.backend.controller;

public final class CartInvalidRequestException extends RuntimeException {
    public CartInvalidRequestException(String message) { super(message); }
}
