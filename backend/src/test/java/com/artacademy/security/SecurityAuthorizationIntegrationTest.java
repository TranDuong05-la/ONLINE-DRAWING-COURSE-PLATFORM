package com.artacademy.security;

import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.repository.UserRepository;
import com.artacademy.service.CustomUserDetailsService;
import com.artacademy.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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