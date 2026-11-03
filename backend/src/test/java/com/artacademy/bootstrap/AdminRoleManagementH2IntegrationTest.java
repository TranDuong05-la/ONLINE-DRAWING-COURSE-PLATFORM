package com.artacademy.bootstrap;

import com.artacademy.entity.User;
import com.artacademy.entity.UserRoleHistory;
import com.artacademy.enums.Role;
import com.artacademy.repository.UserRepository;
import com.artacademy.repository.UserRoleHistoryRepository;
import com.artacademy.service.AuthService;
import com.artacademy.service.CustomUserDetailsService;
import com.artacademy.service.JwtService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AdminRoleManagementH2IntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private UserRoleHistoryRepository history;
    @Autowired private CustomUserDetailsService userDetails;
    @Autowired private JwtService jwt;
    @Autowired private AuthService auth;
    @Autowired private EntityManager entityManager;
    @Autowired private PlatformTransactionManager transactionManager;

    private final List<Integer> fixtureUserIds = new ArrayList<>();

    @AfterEach
    void removeFixtures() {
        if (fixtureUserIds.isEmpty()) return;
        List<UserRoleHistory> histories = history.findAll().stream()
                .filter(row -> fixtureUserIds.contains(row.getUser().getId())
                        || fixtureUserIds.contains(row.getChangedBy().getId())).toList();
        history.deleteAll(histories);
        users.deleteAllById(fixtureUserIds);
        fixtureUserIds.clear();
    }

    @Test
    void roleEndpointUsesAuthenticatedAdminAndAtomicallyWritesHistoryAndInvalidatesOldTokens() throws Exception {
        User admin = user(Role.ADMIN, true);
        User target = user(Role.USER, true);
        String oldTargetToken = bearer(target);
        String oldTargetRefresh = jwt.generateRefreshToken(userDetails.loadUserByUsername(target.getEmail()));

        mvc.perform(put("/api/admin/users/{id}/role", target.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"INSTRUCTOR\",\"reason\":\"approved\",\"actorUserId\":999999}"))
                .andExpect(status().isOk());

        User updated = users.findById(target.getId()).orElseThrow();
        assertThat(updated.getRole()).isEqualTo(Role.INSTRUCTOR);
        assertThat(updated.getTokenVersion()).isEqualTo(2);
        List<UserRoleHistory> entries = history.findHistoryForUser(target.getId());
        assertThat(entries).hasSize(1);
        assertThat(entries.getFirst().getChangedBy().getId()).isEqualTo(admin.getId());
        assertThat(entries.getFirst().getOldRole()).isEqualTo(Role.USER);
        assertThat(entries.getFirst().getNewRole()).isEqualTo(Role.INSTRUCTOR);

        mvc.perform(get("/api/auth/me").header("Authorization", oldTargetToken))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/auth/refresh").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + oldTargetRefresh + "\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/api/auth/me").header("Authorization", bearer(updated)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/users/{id}/role-history", target.getId())
                        .header("Authorization", bearer(updated)))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/users/{id}/role-history", target.getId())
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/admin/users").queryParam("sortBy", "passwordHash")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/users").queryParam("sortBy", "email")
                        .queryParam("sortDirection", "asc")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk());
    }

    @Test
    void patchAndPutStatusContractsBothRemainAvailableAndInvalidateTokens() throws Exception {
        User admin = user(Role.ADMIN, true);
        User target = user(Role.USER, true);
        String oldToken = bearer(target);

        mvc.perform(patch("/api/admin/users/{id}/status", target.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":false}"))
                .andExpect(status().isOk());
        assertThat(users.findById(target.getId()).orElseThrow().getTokenVersion()).isEqualTo(2);
        mvc.perform(get("/api/auth/me").header("Authorization", oldToken)).andExpect(status().isForbidden());

        mvc.perform(put("/api/admin/users/{id}/status", target.getId())
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"isActive\":true}"))
                .andExpect(status().isOk());
        assertThat(users.findById(target.getId()).orElseThrow().getTokenVersion()).isEqualTo(3);
    }

    @Test
    void nonAdminCannotCallRoleMutation() throws Exception {
        User user = user(Role.USER, true);
        User target = user(Role.USER, true);
        mvc.perform(put("/api/admin/users/{id}/role", target.getId())
                        .header("Authorization", bearer(user))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"role\":\"ADMIN\"}"))
                .andExpect(status().isForbidden());
        assertThat(users.findById(target.getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
        assertThat(history.findHistoryForUser(target.getId())).isEmpty();
    }

    @Test
    void roleAndAuditRollbackTogetherWhenTheCallerTransactionRollsBack() {
        User admin = user(Role.ADMIN, true);
        User target = user(Role.USER, true);
        TransactionTemplate tx = new TransactionTemplate(transactionManager);

        assertThrows(IllegalStateException.class, () -> tx.executeWithoutResult(status -> {
            auth.updateUserRole(admin.getId(), target.getId(),
                    new com.artacademy.dto.UpdateUserRoleRequest(Role.STAFF, "temporary"));
            entityManager.flush();
            throw new IllegalStateException("test rollback");
        }));

        assertThat(users.findById(target.getId()).orElseThrow().getRole()).isEqualTo(Role.USER);
        assertThat(users.findById(target.getId()).orElseThrow().getTokenVersion()).isEqualTo(1);
        assertThat(history.findHistoryForUser(target.getId())).isEmpty();
    }

    @Test
    void concurrentRequestsCannotRemoveBothActiveAdministrators() throws Exception {
        User first = user(Role.ADMIN, true);
        User second = user(Role.ADMIN, true);
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> firstChange = pool.submit(() -> tryDemote(start, first, second));
            Future<Boolean> secondChange = pool.submit(() -> tryDemote(start, second, first));
            boolean changedA = firstChange.get(15, TimeUnit.SECONDS);
            boolean changedB = secondChange.get(15, TimeUnit.SECONDS);
            assertThat((changedA ? 1 : 0) + (changedB ? 1 : 0)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(users.countByRoleAndIsActive(Role.ADMIN, true)).isEqualTo(1);
    }

    @Test
    void concurrentRoleAndStatusUpdatesShareTheLastAdminLockProtocol() throws Exception {
        User first = user(Role.ADMIN, true);
        User second = user(Role.ADMIN, true);
        CyclicBarrier start = new CyclicBarrier(2);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> demote = pool.submit(() -> tryDemote(start, first, second));
            Future<Boolean> deactivate = pool.submit(() -> tryDeactivate(start, first));
            boolean roleChanged = demote.get(15, TimeUnit.SECONDS);
            boolean statusChanged = deactivate.get(15, TimeUnit.SECONDS);
            assertThat((roleChanged ? 1 : 0) + (statusChanged ? 1 : 0)).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(users.countByRoleAndIsActive(Role.ADMIN, true)).isEqualTo(1);
    }

    private boolean tryDemote(CyclicBarrier start, User actor, User target) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try {
            auth.updateUserRole(actor.getId(), target.getId(),
                    new com.artacademy.dto.UpdateUserRoleRequest(Role.USER, null));
            return true;
        } catch (com.artacademy.exception.BusinessException | com.artacademy.exception.UnauthorizedException expected) {
            return false;
        }
    }

    private boolean tryDeactivate(CyclicBarrier start, User target) throws Exception {
        start.await(5, TimeUnit.SECONDS);
        try {
            auth.updateUserStatus(target.getId(), false);
            return true;
        } catch (com.artacademy.exception.BusinessException expected) {
            return false;
        }
    }

    private User user(Role role, boolean active) {
        String email = "a1-rbac-" + UUID.randomUUID() + "@example.test";
        User saved = users.saveAndFlush(User.builder().fullName("A1 test " + role).email(email)
                .passwordHash("test-hash").role(role).isActive(active).tokenVersion(1).build());
        fixtureUserIds.add(saved.getId());
        return saved;
    }

    private String bearer(User user) {
        return "Bearer " + jwt.generateAccessToken(userDetails.loadUserByUsername(user.getEmail()));
    }
}
