package com.drawingcourse.backend.service;

/** Raised only when a required trusted Cart catalog/availability provider cannot answer. */
public final class CartCatalogUnavailableException extends RuntimeException {
    public CartCatalogUnavailableException(String message) { super(message); }
    public CartCatalogUnavailableException(String message, Throwable cause) { super(message, cause); }
}
