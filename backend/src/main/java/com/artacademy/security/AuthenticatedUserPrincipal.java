package com.artacademy.security;

import com.artacademy.enums.Role;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.userdetails.UserDetails;

import java.util.List;
import java.util.Objects;

/** Trusted application principal constructed from a persisted User after authentication. */
public final class AuthenticatedUserPrincipal implements UserDetails {
    private final Integer userId;
    private final String email;
    private final String passwordHash;
    private final Role role;
    private final boolean active;
    private final int tokenVersion;

    public AuthenticatedUserPrincipal(Integer userId, String email, String passwordHash, Role role, boolean active) {
        this(userId, email, passwordHash, role, active, 1);
    }

    public AuthenticatedUserPrincipal(Integer userId, String email, String passwordHash, Role role, boolean active,
                                      Integer tokenVersion) {
        this.userId = Objects.requireNonNull(userId, "userId");
        if (userId <= 0) throw new IllegalArgumentException("userId must be positive");
        this.email = Objects.requireNonNull(email, "email");
        this.passwordHash = Objects.requireNonNull(passwordHash, "passwordHash");
        this.role = Objects.requireNonNull(role, "role");
        this.active = active;
        if (tokenVersion == null || tokenVersion < 1) throw new IllegalArgumentException("tokenVersion must be positive");
        this.tokenVersion = tokenVersion;
    }

    public Integer getUserId() { return userId; }
    public Role getRole() { return role; }
    public int getTokenVersion() { return tokenVersion; }

    @Override public List<SimpleGrantedAuthority> getAuthorities() {
        return List.of(new SimpleGrantedAuthority("ROLE_" + role.name()));
    }

    @Override public String getPassword() { return passwordHash; }
    @Override public String getUsername() { return email; }
    @Override public boolean isAccountNonExpired() { return true; }
    @Override public boolean isAccountNonLocked() { return active; }
    @Override public boolean isCredentialsNonExpired() { return true; }
    @Override public boolean isEnabled() { return active; }
}
