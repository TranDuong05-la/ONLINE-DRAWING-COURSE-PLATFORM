package com.artacademy.security;

import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.repository.UserRepository;
import com.artacademy.service.CustomUserDetailsService;
import com.artacademy.service.JwtService;
import io.jsonwebtoken.Jwts;
import javax.crypto.SecretKey;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationContext;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.security.core.annotation.AuthenticationPrincipal;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(SecurityAuthorizationIntegrationTest.IdentityProbe.class)
class SecurityAuthorizationIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private CustomUserDetailsService userDetailsService;
    @Autowired private JwtService jwtService;
    @Autowired private ApplicationContext applicationContext;

    @Test
    void ipnBoundaryIsNotRegisteredOrPublicByDefault() throws Exception {
        org.junit.jupiter.api.Assertions.assertTrue(applicationContext.getBeansOfType(
                com.artacademy.payment.vnpay.VnPayIpnController.class).isEmpty());
        mvc.perform(get("/api/payments/vnpay/ipn")).andExpect(status().isUnauthorized());
    }

    @Test
    void missingAndMalformedTokensAreRejectedOnAuthenticatedRoutes() throws Exception {
        mvc.perform(get("/__phase4/identity")).andExpect(status().isUnauthorized());
        mvc.perform(get("/__phase4/identity").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void applicationUsesPersistedIdentityAndIgnoresSuppliedUserId() throws Exception {
        User staff = createUser(Role.STAFF);
        User admin = createUser(Role.ADMIN);
        mvc.perform(get("/__phase4/identity")
                        .header("Authorization", bearer(staff))
                        .header("X-User-Id", admin.getId().toString())
                        .param("userId", admin.getId().toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value(staff.getId()))
                .andExpect(jsonPath("$.email").value(staff.getEmail()))
                .andExpect(jsonPath("$.authorities[0]").value("ROLE_STAFF"));
    }

    @Test
    void invalidSignatureExpiredAndRefreshTokensAreRejectedAtHttpBoundary() throws Exception {
        User user = createUser(Role.USER);
        UserDetails persistedPrincipal = userDetailsService.loadUserByUsername(user.getEmail());

        String otherKey = java.util.Base64.getEncoder().encodeToString(
                "different-security-test-key-material-at-least-32-bytes".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String wrongSignature = new JwtService(otherKey, 60_000, 120_000).generateAccessToken(persistedPrincipal);
        mvc.perform(get("/__phase4/identity").header("Authorization", "Bearer " + wrongSignature))
                .andExpect(status().isUnauthorized());

        SecretKey signingKey = (SecretKey) ReflectionTestUtils.getField(jwtService, "signingKey");
        long now = System.currentTimeMillis();
        String expiredAccess = Jwts.builder().subject(user.getEmail()).claim("type", "ACCESS")
                .issuedAt(new Date(now - 120_000)).expiration(new Date(now - 60_000))
                .signWith(signingKey).compact();
        mvc.perform(get("/__phase4/identity").header("Authorization", "Bearer " + expiredAccess))
                .andExpect(status().isUnauthorized());

        String refreshAsAccess = jwtService.generateRefreshToken(persistedPrincipal);
        mvc.perform(get("/__phase4/identity").header("Authorization", "Bearer " + refreshAsAccess))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void inactivePersistedUserIsForbiddenEvenWithValidAccessToken() throws Exception {
        User inactive = users.saveAndFlush(User.builder().fullName("Inactive security test")
                .email("inactive-" + UUID.randomUUID() + "@example.test")
                .passwordHash("test-hash").role(Role.USER).isActive(false).build());

        mvc.perform(get("/__phase4/identity").header("Authorization", bearer(inactive)))
                .andExpect(status().isForbidden());
    }
    @Test
    void adminRouteRejectsStaffAndAllowsAdminAuthority() throws Exception {
        User staff = createUser(Role.STAFF);
        User admin = createUser(Role.ADMIN);
        mvc.perform(get("/api/admin/__phase4_security_probe__").header("Authorization", bearer(staff)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/__phase4_security_probe__").header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.authorized").value(true));
    }

    @Test
    void securityContextDoesNotLeakBetweenMockMvcRequests() throws Exception {
        User staff = createUser(Role.STAFF);
        mvc.perform(get("/__phase4/identity").header("Authorization", bearer(staff)))
                .andExpect(status().isOk());
        mvc.perform(get("/__phase4/identity")).andExpect(status().isUnauthorized());
    }

    private User createUser(Role role) {
        String email = "phase4-" + UUID.randomUUID() + "@example.test";
        return users.saveAndFlush(User.builder().fullName("Phase 4 Test").email(email)
                .passwordHash("test-hash").role(role).isActive(true).build());
    }

    private String bearer(User user) {
        UserDetails principal = userDetailsService.loadUserByUsername(user.getEmail());
        return "Bearer " + jwtService.generateAccessToken(principal);
    }

    @RestController
    static class IdentityProbe {
        @GetMapping("/__phase4/identity")
        Map<String, Object> identity(@AuthenticationPrincipal AuthenticatedUserPrincipal principal) {
            return Map.of("userId", principal.getUserId(), "email", principal.getUsername(),
                    "authorities", principal.getAuthorities().stream().map(a -> a.getAuthority()).toList());
        }

        @GetMapping("/api/admin/__phase4_security_probe__")
        Map<String, Object> adminProbe() { return Map.of("authorized", true); }
    }
}
