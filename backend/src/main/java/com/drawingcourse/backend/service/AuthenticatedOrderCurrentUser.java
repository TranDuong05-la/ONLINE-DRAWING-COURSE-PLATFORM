package com.drawingcourse.backend.service;

import com.artacademy.security.AuthenticatedUserIdResolver;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Resolves Order ownership only from A's authenticated, persisted-user principal. */
@Component
public final class AuthenticatedOrderCurrentUser implements OrderCurrentUser {
    private final AuthenticatedUserIdResolver resolver;

    public AuthenticatedOrderCurrentUser(AuthenticatedUserIdResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver);
    }

    @Override
    public Integer requireUserId() {
        return resolver.requireUserId();
    }
}
