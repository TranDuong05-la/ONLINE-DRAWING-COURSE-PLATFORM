package com.drawingcourse.backend.service;

/** Must resolve the real user ID from verified authentication. No default adapter. */
@FunctionalInterface
public interface OrderCurrentUser {
    Integer requireUserId();
}
