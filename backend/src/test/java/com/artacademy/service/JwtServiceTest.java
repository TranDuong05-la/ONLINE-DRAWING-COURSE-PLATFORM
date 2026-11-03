package com.artacademy.service;

import com.artacademy.enums.Role;
import com.artacademy.security.AuthenticatedUserPrincipal;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.userdetails.UserDetails;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

class JwtServiceTest {
    private static final String TEST_KEY = Base64.getEncoder().encodeToString(
            "phase4-test-only-key-material-at-least-32-bytes".getBytes(StandardCharsets.UTF_8));
    private static final String OTHER_TEST_KEY = Base64.getEncoder().encodeToString(
            "different-test-only-key-material-at-least-32".getBytes(StandardCharsets.UTF_8));
    private final JwtService jwt = new JwtService(TEST_KEY, 60_000, 120_000);
    private final UserDetails principal = new AuthenticatedUserPrincipal(
            19, "jwt-user@example.test", "hash", Role.USER, true);

    @Test
    void accessTokenPreservesEmailSubjectAndValidatesForDatabasePrincipal() {
        String token = jwt.generateAccessToken(principal);
        assertThat(jwt.extractUsername(token)).isEqualTo("jwt-user@example.test");
        assertThat(jwt.isTokenValid(token, principal)).isTrue();
    }

    @Test
    void rejectsWrongSignatureAndRefreshTokenAsAccessToken() {
        String signedByOtherKey = new JwtService(OTHER_TEST_KEY, 60_000, 120_000).generateAccessToken(principal);
        assertThat(jwt.isTokenValid(signedByOtherKey, principal)).isFalse();
        assertThat(jwt.isTokenValid(jwt.generateRefreshToken(principal), principal)).isFalse();
    }

    @Test
    void rejectsExpiredCorrectlySignedAccessToken() {
        var key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(TEST_KEY));
        long now = System.currentTimeMillis();
        String expired = Jwts.builder().subject(principal.getUsername()).claim("type", "ACCESS")
                .issuedAt(new Date(now - 120_000)).expiration(new Date(now - 60_000))
                .signWith(key).compact();

        assertThat(jwt.isTokenValid(expired, principal)).isFalse();
    }

    @Test
    void roleOrStatusChangeInvalidatesAccessAndRefreshTokensByVersion() {
        UserDetails versionOne = new AuthenticatedUserPrincipal(19, "jwt-user@example.test", "hash", Role.USER, true, 1);
        UserDetails versionTwo = new AuthenticatedUserPrincipal(19, "jwt-user@example.test", "hash", Role.USER, true, 2);
        String access = jwt.generateAccessToken(versionOne);
        String refresh = jwt.generateRefreshToken(versionOne);

        assertThat(jwt.isTokenValid(access, versionOne)).isTrue();
        assertThat(jwt.isTokenValid(access, versionTwo)).isFalse();
        assertThat(jwt.isRefreshTokenValid(refresh, 1)).isTrue();
        assertThat(jwt.isRefreshTokenValid(refresh, 2)).isFalse();
    }

    @Test
    void preVersionTokensRemainValidAtInitialGenerationOnly() {
        var key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(TEST_KEY));
        long now = System.currentTimeMillis();
        String legacy = Jwts.builder().subject(principal.getUsername()).claim("type", "ACCESS")
                .issuedAt(new Date(now)).expiration(new Date(now + 60_000)).signWith(key).compact();
        UserDetails initial = new AuthenticatedUserPrincipal(19, principal.getUsername(), "hash", Role.USER, true, 1);
        UserDetails changed = new AuthenticatedUserPrincipal(19, principal.getUsername(), "hash", Role.USER, true, 2);

        assertThat(jwt.isTokenValid(legacy, initial)).isTrue();
        assertThat(jwt.isTokenValid(legacy, changed)).isFalse();
    }
}
