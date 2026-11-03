package com.artacademy.config;

import com.artacademy.enums.Role;
import com.artacademy.security.AuthenticatedUserPrincipal;
import com.artacademy.service.CustomUserDetailsService;
import com.artacademy.service.JwtService;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.security.core.context.SecurityContextHolder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {
    private static final String TEST_KEY = Base64.getEncoder().encodeToString(
            "phase4-test-only-key-material-at-least-32-bytes".getBytes(StandardCharsets.UTF_8));
    private static final String OTHER_TEST_KEY = Base64.getEncoder().encodeToString(
            "different-test-only-key-material-at-least-32".getBytes(StandardCharsets.UTF_8));
    private final JwtService jwt = new JwtService(TEST_KEY, 60_000, 120_000);
    private final AuthenticatedUserPrincipal principal = new AuthenticatedUserPrincipal(
            42, "trusted@example.test", "hash", Role.STAFF, true);
    @Mock private CustomUserDetailsService users;
    private ExposedFilter filter;

    @BeforeEach void setUp() { SecurityContextHolder.clearContext(); filter = new ExposedFilter(jwt, users); }
    @AfterEach void clearContext() { SecurityContextHolder.clearContext(); }

    @Test
    void missingJwtContinuesAnonymously() throws Exception {
        invoke(null, null);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(users);
    }

    @Test
    void invalidSignatureDoesNotCreateAuthentication() throws Exception {
        String invalid = new JwtService(OTHER_TEST_KEY, 60_000, 120_000).generateAccessToken(principal);
        invoke("Bearer " + invalid, null);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(users);
    }

    @Test
    void expiredJwtDoesNotCreateAuthentication() throws Exception {
        var key = Keys.hmacShaKeyFor(Base64.getDecoder().decode(TEST_KEY));
        long now = System.currentTimeMillis();
        String expired = Jwts.builder().subject(principal.getUsername()).claim("type", "ACCESS")
                .issuedAt(new Date(now - 120_000)).expiration(new Date(now - 60_000))
                .signWith(key).compact();
        invoke("Bearer " + expired, null);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(users);
    }

    @Test
    void validJwtInstallsPersistedPrincipalAndIgnoresClientUserId() throws Exception {
        when(users.loadUserByUsername(principal.getUsername())).thenReturn(principal);
        invoke("Bearer " + jwt.generateAccessToken(principal), "999");
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        assertThat(authentication.getPrincipal()).isSameAs(principal);
        assertThat(((AuthenticatedUserPrincipal) authentication.getPrincipal()).getUserId()).isEqualTo(42);
        assertThat(authentication.getName()).isEqualTo("trusted@example.test");
        assertThat(authentication.getAuthorities()).extracting("authority").containsExactly("ROLE_STAFF");
    }

    private void invoke(String authorization, String suppliedUserId) throws Exception {
        var request = new MockHttpServletRequest("GET", "/protected");
        if (authorization != null) request.addHeader("Authorization", authorization);
        if (suppliedUserId != null) {
            request.addHeader("X-User-Id", suppliedUserId);
            request.setParameter("userId", suppliedUserId);
        }
        filter.invoke(request, new MockHttpServletResponse(), new MockFilterChain());
    }

    private static final class ExposedFilter extends JwtAuthenticationFilter {
        ExposedFilter(JwtService jwt, CustomUserDetailsService users) { super(jwt, users); }
        void invoke(jakarta.servlet.http.HttpServletRequest req, jakarta.servlet.http.HttpServletResponse res,
                    FilterChain chain) throws java.io.IOException, jakarta.servlet.ServletException {
            super.doFilterInternal(req, res, chain);
        }
    }
}