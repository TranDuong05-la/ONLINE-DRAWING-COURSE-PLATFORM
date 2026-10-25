package com.drawingcourse.backend.service;

/** Implement using verified authentication, never a request user ID. No default adapter. */
@FunctionalInterface
public interface CartCurrentUser {
    Integer requireUserId();
}
