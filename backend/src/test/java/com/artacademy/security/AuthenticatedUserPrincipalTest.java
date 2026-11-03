package com.artacademy.security;

import com.artacademy.enums.Role;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthenticatedUserPrincipalTest {
    @Test
    void principalCarriesPersistedIdEmailAndExistingRoleAuthority() {
        var principal = new AuthenticatedUserPrincipal(42, "student@example.test", "hash", Role.STAFF, true);

        assertThat(principal.getUserId()).isEqualTo(42);
        assertThat(principal.getUsername()).isEqualTo("student@example.test");
        assertThat(principal.getAuthorities()).extracting("authority").containsExactly("ROLE_STAFF");
        assertThat(principal.isEnabled()).isTrue();
    }

    @Test
    void principalRejectsMissingOrInvalidPersistedIdentity() {
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> new AuthenticatedUserPrincipal(null, "x@example.test", "hash", Role.USER, true));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new AuthenticatedUserPrincipal(0, "x@example.test", "hash", Role.USER, true));
    }
}