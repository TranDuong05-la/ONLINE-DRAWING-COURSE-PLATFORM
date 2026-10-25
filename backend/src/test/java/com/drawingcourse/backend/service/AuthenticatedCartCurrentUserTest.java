package com.drawingcourse.backend.service;

import com.artacademy.enums.Role;
import com.artacademy.security.AuthenticatedUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthenticatedCartCurrentUserTest {
    private final AuthenticatedCartCurrentUser currentUser =
            new AuthenticatedCartCurrentUser(new com.artacademy.security.AuthenticatedUserIdResolver());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesPersistedIntegerIdFromAuthenticatedPrincipal() {
        var principal = new AuthenticatedUserPrincipal(42, "student@example.test", "unused", Role.USER, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        assertEquals(42, currentUser.requireUserId());
    }

    @Test
    void rejectsMissingAuthenticationInsteadOfAcceptingSuppliedIdentity() {
        SecurityContextHolder.clearContext();

        assertThrows(org.springframework.security.authentication.AuthenticationCredentialsNotFoundException.class,
                currentUser::requireUserId);
    }
}
