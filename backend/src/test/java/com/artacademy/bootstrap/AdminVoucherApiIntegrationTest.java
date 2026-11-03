package com.artacademy.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.repository.UserRepository;
import com.artacademy.service.CustomUserDetailsService;
import com.artacademy.service.JwtService;
import com.drawingcourse.backend.entity.Order;
import com.drawingcourse.backend.entity.OrderLineSnapshot;
import com.drawingcourse.backend.entity.OrderItemType;
import com.drawingcourse.backend.entity.OrderVoucher;
import com.drawingcourse.backend.entity.Voucher;
import com.drawingcourse.backend.entity.VoucherClaim;
import com.drawingcourse.backend.entity.VoucherClaimJpaEntity;
import com.drawingcourse.backend.entity.VoucherJpaEntity;
import com.drawingcourse.backend.repository.VoucherClaimRepository;
import com.drawingcourse.backend.repository.VoucherRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.EntityManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = ArtAcademyApplication.class, properties = {
        "app.voucher.admin-api-enabled=true",
        "spring.datasource.url=jdbc:h2:mem:admin-voucher-api;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class AdminVoucherApiIntegrationTest {
    private static final Instant FROM = Instant.parse("2026-10-31T01:00:00Z");
    private static final Instant UNTIL = Instant.parse("2026-11-30T01:00:00Z");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired UserRepository users;
    @Autowired CustomUserDetailsService userDetails;
    @Autowired JwtService jwt;
    @Autowired EntityManager em;
    @Autowired VoucherRepository vouchers;
    @Autowired VoucherClaimRepository claims;

    @Test
    void onlyAdminCanUseEnabledApi() throws Exception {
        mvc.perform(get("/api/admin/vouchers")).andExpect(status().isUnauthorized());
        for (Role role : List.of(Role.USER, Role.INSTRUCTOR, Role.STAFF)) {
            mvc.perform(get("/api/admin/vouchers").header("Authorization", bearer(createUser(role))))
                    .andExpect(status().isForbidden());
        }
        mvc.perform(get("/api/admin/vouchers").header("Authorization", bearer(createUser(Role.ADMIN))))
                .andExpect(status().isOk());
    }

    @Test
    void createsFixedAndPercentageVouchersAndPreservesExactCase() throws Exception {
        String token = bearer(createUser(Role.ADMIN));
        create(token, fixed("SAVE10", 10_000L, 0L, 0L)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value("SAVE10"))
                .andExpect(jsonPath("$.data.discountType").value("FIXED_VND"))
                .andExpect(jsonPath("$.data.definitionVersion").value(1))
                .andExpect(jsonPath("$.data.totalUsageLimit").value(0))
                .andExpect(jsonPath("$.data.perUserUsageLimit").value(0));
        create(token, fixed("save10", 20_000L, null, null)).andExpect(status().isCreated());
        create(token, percentage("PERCENT15", 1_500L, 50_000L)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.maximumDiscountVnd").value(50_000));
        create(token, percentage("UNCAPPED", 1_000L, null)).andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.maximumDiscountVnd").doesNotExist());
        create(token, fixed("SAVE10", 1L, null, null)).andExpect(status().isConflict());
        assertThat(vouchers.findByCode("SAVE10")).isPresent();
        assertThat(vouchers.findByCode("save10")).isPresent();
    }

    @Test
    void createRejectsInvalidDiscountCapQuotaAndFixedCapCombinations() throws Exception {
        String token = bearer(createUser(Role.ADMIN));
        Map<String, Object> zero = fixed("ZERO", 0L, null, null);
        create(token, zero).andExpect(status().isBadRequest());
        Map<String, Object> negative = fixed("NEGATIVE", -1L, null, null);
        create(token, negative).andExpect(status().isBadRequest());
        create(token, percentage("ZERO-CAP", 1_000L, 0L)).andExpect(status().isBadRequest());
        create(token, percentage("NEG-CAP", 1_000L, -1L)).andExpect(status().isBadRequest());
        Map<String, Object> negativeQuota = fixed("NEG-QUOTA", 1L, -1L, 1L);
        create(token, negativeQuota).andExpect(status().isBadRequest());
        Map<String, Object> fixedCap = fixed("FIXED-CAP", 1L, null, null);
        fixedCap.put("maximumDiscountVnd", 1L);
        create(token, fixedCap).andExpect(status().isBadRequest());
    }

    @Test
    void listAndDetailExposeStableAdminFieldsClaimsAndCountersWithoutSensitiveDataOrWrites() throws Exception {
        String token = bearer(createUser(Role.ADMIN));
        VoucherJpaEntity older = persist(definition("READ-A", true, 1_000));
        older.reserveOne();
        older.reserveOne();
        older.redeemOne();
        vouchers.saveAndFlush(older);
        VoucherJpaEntity newer = persist(definition("READ-B", true, 2_000));
        persistClaim(newer, VoucherClaim.State.RELEASED, 910_001);
        em.flush();
        long reservedBefore = newer.getReservedCount();
        long redeemedBefore = newer.getRedeemedCount();

        mvc.perform(get("/api/admin/vouchers").header("Authorization", token).param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.size").value(1))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].id").value(newer.getId()))
                .andExpect(jsonPath("$.data.content[0].hasClaims").value(true))
                .andExpect(jsonPath("$.data.content[0].financialTermsEditable").value(false))
                .andExpect(jsonPath("$.data.content[0].claimId").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].paymentReference").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].version").doesNotExist());

        mvc.perform(get("/api/admin/vouchers/{id}", older.getId()).header("Authorization", token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reservedCount").value(1))
                .andExpect(jsonPath("$.data.redeemedCount").value(1))
                .andExpect(jsonPath("$.data.hasClaims").value(false));
        mvc.perform(get("/api/admin/vouchers/{id}", 2_000_000_000).header("Authorization", token))
                .andExpect(status().isNotFound());
        em.clear();
        VoucherJpaEntity unchanged = vouchers.findById(newer.getId()).orElseThrow();
        assertThat(unchanged.getReservedCount()).isEqualTo(reservedBefore);
        assertThat(unchanged.getRedeemedCount()).isEqualTo(redeemedBefore);
        assertThat(claims.existsByVoucherId(newer.getId())).isTrue();
    }

    @Test
    void paginationBoundsAreValidated() throws Exception {
        String token = bearer(createUser(Role.ADMIN));
        mvc.perform(get("/api/admin/vouchers").header("Authorization", token).param("page", "-1"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/vouchers").header("Authorization", token).param("size", "0"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/vouchers").header("Authorization", token).param("size", "51"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/vouchers").header("Authorization", token).param("size", "50"))
                .andExpect(status().isOk());
    }

    @Test
    void financialUpdateBeforeClaimSupportsNoOpAndRejectsStaleOrForbiddenFields() throws Exception {
        String token = bearer(createUser(Role.ADMIN));
        VoucherJpaEntity row = persist(definition("EDITABLE", true, 1_000));
        Map<String, Object> update = update(row.getDefinitionVersion(), "PERCENT_BASIS_POINTS", 2_000L, 25_000L);
        mvc.perform(put("/api/admin/vouchers/{id}", row.getId()).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(update)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.definitionVersion").value(2))
                .andExpect(jsonPath("$.data.discountValue").value(2_000));

        mvc.perform(put("/api/admin/vouchers/{id}", row.getId()).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(update)))
                .andExpect(status().isConflict());
        update.put("expectedDefinitionVersion", 2L);
        mvc.perform(put("/api/admin/vouchers/{id}", row.getId()).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(update)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.definitionVersion").value(2));
        update.put("discountValue", 0L);
        mvc.perform(put("/api/admin/vouchers/{id}", row.getId()).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(update)))
                .andExpect(status().isBadRequest());
        update.put("discountValue", 2_000L);
        update.put("code", "OTHER");
        mvc.perform(put("/api/admin/vouchers/{id}", row.getId()).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(update)))
                .andExpect(status().isBadRequest());
        update.remove("code");
        update.put("active", false);
        mvc.perform(put("/api/admin/vouchers/{id}", row.getId()).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(update)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void everyHistoricalClaimStateMakesFinancialTermsImmutable() throws Exception {
        String token = bearer(createUser(Role.ADMIN));
        int orderId = 920_000;
        for (VoucherClaim.State state : VoucherClaim.State.values()) {
            VoucherJpaEntity row = persist(definition("CLAIM-" + state, true, 1_000));
            persistClaim(row, state, orderId++);
            Map<String, Object> update = update(1L, "FIXED_VND", 2_000L, null);
            mvc.perform(put("/api/admin/vouchers/{id}", row.getId()).header("Authorization", token)
                            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(update)))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.message").value("Voucher financial terms are immutable after the first claim"));
        }
    }

    @Test
    void statusChangesVersionButNoOpDoesNotAndLegacyZeroVoucherRemainsManageable() throws Exception {
        String token = bearer(createUser(Role.ADMIN));
        Voucher legacy = new Voucher("LEGACY-ZERO", OrderVoucher.Kind.PERCENT_BASIS_POINTS, 0,
                OptionalLong.of(0), true, FROM, UNTIL, 0, OptionalLong.empty(), OptionalLong.empty());
        VoucherJpaEntity row = persist(legacy);
        persistClaim(row, VoucherClaim.State.RESERVED, 930_001);
        em.flush();
        Instant updatedBefore = row.getUpdatedAt();

        mvc.perform(patch("/api/admin/vouchers/{id}/status", row.getId()).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("active", true, "expectedDefinitionVersion", 1))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.definitionVersion").value(1));
        em.flush();
        assertThat(row.getUpdatedAt()).isEqualTo(updatedBefore);

        mvc.perform(patch("/api/admin/vouchers/{id}/status", row.getId()).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("active", false, "expectedDefinitionVersion", 1))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.definitionVersion").value(2))
                .andExpect(jsonPath("$.data.active").value(false));
        mvc.perform(patch("/api/admin/vouchers/{id}/status", row.getId()).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("active", true, "expectedDefinitionVersion", 2))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.definitionVersion").value(3))
                .andExpect(jsonPath("$.data.active").value(true));
        mvc.perform(patch("/api/admin/vouchers/{id}/status", row.getId()).header("Authorization", token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("active", true, "expectedDefinitionVersion", 1))))
                .andExpect(status().isConflict());
        assertThat(claims.findByOrderIdAndUserId(930_001, 77).orElseThrow().getState())
                .isEqualTo(VoucherClaim.State.RESERVED);
    }

    private org.springframework.test.web.servlet.ResultActions create(String token, Map<String, Object> body)
            throws Exception {
        return mvc.perform(post("/api/admin/vouchers").header("Authorization", token)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)));
    }

    private Map<String, Object> fixed(String code, Long value, Long total, Long perUser) {
        Map<String, Object> body = base(code, "FIXED_VND", value);
        body.put("totalUsageLimit", total);
        body.put("perUserUsageLimit", perUser);
        return body;
    }

    private Map<String, Object> percentage(String code, Long value, Long cap) {
        Map<String, Object> body = base(code, "PERCENT_BASIS_POINTS", value);
        body.put("maximumDiscountVnd", cap);
        return body;
    }

    private Map<String, Object> base(String code, String kind, Long value) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", code);
        body.put("discountType", kind);
        body.put("discountValue", value);
        body.put("active", true);
        body.put("validFrom", FROM.toString());
        body.put("validUntil", UNTIL.toString());
        body.put("minimumEligibleMerchandiseSpendVnd", 0L);
        return body;
    }

    private Map<String, Object> update(long version, String kind, long value, Long cap) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("expectedDefinitionVersion", version);
        body.put("discountType", kind);
        body.put("discountValue", value);
        body.put("maximumDiscountVnd", cap);
        body.put("validFrom", FROM.toString());
        body.put("validUntil", UNTIL.toString());
        body.put("minimumEligibleMerchandiseSpendVnd", 0L);
        body.put("totalUsageLimit", 10L);
        body.put("perUserUsageLimit", 2L);
        return body;
    }

    private Voucher definition(String code, boolean active, long value) {
        return new Voucher(code, OrderVoucher.Kind.FIXED_VND, value, OptionalLong.empty(), active,
                FROM, UNTIL, 0, OptionalLong.of(10), OptionalLong.of(2));
    }

    private VoucherJpaEntity persist(Voucher definition) {
        VoucherJpaEntity row = VoucherJpaEntity.fromDefinition(definition);
        em.persist(row);
        em.flush();
        return row;
    }

    private void persistClaim(VoucherJpaEntity row, VoucherClaim.State state, int orderId) {
        Instant now = FROM.plusSeconds(60);
        Voucher definition = new com.drawingcourse.backend.service.VoucherPersistenceMapper().toDomain(row);
        Order order = Order.create(77, List.of(new OrderLineSnapshot(
                OrderItemType.COURSE, orderId, "Claim course", 1, 10_000, true)), 0,
                Optional.of(definition.toOrderSnapshot()), Clock.fixed(now, ZoneOffset.UTC));
        VoucherClaim claim = VoucherClaim.reserved("admin-test:" + orderId, row.getId(),
                row.getDefinitionVersion(), orderId, order, now);
        if (state == VoucherClaim.State.REDEEMED) claim = claim.redeem("payment-" + orderId,
                now.plusSeconds(1), now.plusSeconds(2), now.plusSeconds(3));
        if (state == VoucherClaim.State.RELEASED) claim = claim.releaseUnpaid(
                VoucherClaim.ReleaseReason.CANCELLED, now.plusSeconds(1));
        em.persist(VoucherClaimJpaEntity.fromSnapshot(claim));
        em.flush();
    }

    private User createUser(Role role) {
        return users.saveAndFlush(User.builder().fullName("Admin Voucher Test")
                .email(role + "-" + UUID.randomUUID() + "@example.test")
                .passwordHash("test-hash").role(role).isActive(true).build());
    }

    private String bearer(User user) {
        return "Bearer " + jwt.generateAccessToken(userDetails.loadUserByUsername(user.getEmail()));
    }
}
