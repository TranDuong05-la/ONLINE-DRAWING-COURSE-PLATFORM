package com.artacademy.security;

import com.artacademy.enums.Role;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AuthenticatedUserIdResolverTest {
    private final AuthenticatedUserIdResolver resolver = new AuthenticatedUserIdResolver();

    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void resolvesOnlyTheTypedAuthenticatedPrincipal() {
        var principal = new AuthenticatedUserPrincipal(71, "user@example.test", "hash", Role.USER, true);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities()));

        assertThat(resolver.requireUserId()).isEqualTo(71);
    }

    @Test
    void rejectsMissingAndUntypedPrincipals() {
        SecurityContextHolder.clearContext();
        assertThrows(AuthenticationCredentialsNotFoundException.class, resolver::requireUserId);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("client-controlled", null, java.util.List.of()));
        assertThrows(AuthenticationCredentialsNotFoundException.class, resolver::requireUserId);
    }
}