package com.artacademy.service;

import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.repository.UserRepository;
import com.artacademy.security.AuthenticatedUserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

class CustomUserDetailsServiceTest {
    private final UserRepository users = mock(UserRepository.class);
    private final CustomUserDetailsService service = new CustomUserDetailsService(users);

    @Test
    void mapsPersistedUserIdEmailAndRoleToTypedPrincipal() {
        when(users.findByEmail("student@example.test")).thenReturn(Optional.of(User.builder()
                .id(23).email("student@example.test").passwordHash("hash").role(Role.INSTRUCTOR)
                .isActive(true).fullName("Student").build()));

        var result = service.loadUserByUsername(" Student@example.test ");

        assertThat(result).isInstanceOf(AuthenticatedUserPrincipal.class);
        var principal = (AuthenticatedUserPrincipal) result;
        assertThat(principal.getUserId()).isEqualTo(23);
        assertThat(principal.getUsername()).isEqualTo("student@example.test");
        assertThat(principal.getAuthorities()).extracting("authority").containsExactly("ROLE_INSTRUCTOR");
        verify(users).findByEmail("student@example.test");
    }

    @Test
    void doesNotCreatePrincipalForUnknownUser() {
        when(users.findByEmail("missing@example.test")).thenReturn(Optional.empty());
        assertThrows(UsernameNotFoundException.class, () -> service.loadUserByUsername("missing@example.test"));
    }
}