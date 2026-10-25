package com.drawingcourse.backend.service;

import com.artacademy.security.AuthenticatedUserIdResolver;
import org.springframework.stereotype.Component;

import java.util.Objects;

/** Cart identity adapter backed exclusively by A's validated JWT principal. */
@Component
public final class AuthenticatedCartCurrentUser implements CartCurrentUser {
    private final AuthenticatedUserIdResolver userIdResolver;

    public AuthenticatedCartCurrentUser(AuthenticatedUserIdResolver userIdResolver) {
        this.userIdResolver = Objects.requireNonNull(userIdResolver);
    }

    @Override
    public Integer requireUserId() {
        return userIdResolver.requireUserId();
    }
}
