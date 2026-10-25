package com.artacademy.security;

import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.stereotype.Component;

/** Resolves ownership only from the principal installed by verified authentication. */
@Component
public final class AuthenticatedUserIdResolver {
    public Integer requireUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || authentication instanceof AnonymousAuthenticationToken
                || !(authentication.getPrincipal() instanceof AuthenticatedUserPrincipal principal)) {
            throw new AuthenticationCredentialsNotFoundException("Authenticated user identity is unavailable");
        }
        return principal.getUserId();
    }
}