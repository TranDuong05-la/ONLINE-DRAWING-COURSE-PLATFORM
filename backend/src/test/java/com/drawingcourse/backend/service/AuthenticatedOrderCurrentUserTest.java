package com.drawingcourse.backend.service;

import com.artacademy.enums.Role;
import com.artacademy.security.AuthenticatedUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.*;

class AuthenticatedOrderCurrentUserTest {
    private final AuthenticatedOrderCurrentUser currentUser = new AuthenticatedOrderCurrentUser(
            new com.artacademy.security.AuthenticatedUserIdResolver());

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void returnsOnlyTheAuthenticatedPrincipalIntegerId() {
        var principal = new AuthenticatedUserPrincipal(57, "owner@example.test", "unused", Role.USER, true);
        SecurityContextHolder.getContext().setAuthentication(new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities()));
        assertEquals(57, currentUser.requireUserId());
    }

    @Test void missingAuthenticationIsRejected() {
        SecurityContextHolder.clearContext();
        assertThrows(AuthenticationCredentialsNotFoundException.class, currentUser::requireUserId);
    }
}
