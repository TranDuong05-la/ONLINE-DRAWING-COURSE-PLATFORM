package com.artacademy.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.repository.UserRepository;
import com.artacademy.service.CustomUserDetailsService;
import com.artacademy.service.JwtService;
import com.drawingcourse.backend.entity.OrderVoucher;
import com.drawingcourse.backend.entity.Voucher;
import com.drawingcourse.backend.entity.VoucherJpaEntity;
import com.drawingcourse.backend.entity.VoucherUserUsageJpaEntity;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest(classes = ArtAcademyApplication.class, properties = {
        "app.voucher.api-enabled=true",
        "spring.datasource.url=jdbc:h2:mem:voucher-catalog-api;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Transactional
class VoucherCatalogApiIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired CustomUserDetailsService userDetails;
    @Autowired JwtService jwt;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;

    @Test void authenticatedListUsesDefaultsStableTermsAndNoInternalFieldsOrWrites() throws Exception {
        User owner = createUser("owner");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        createVoucher("B-CATALOG", OrderVoucher.Kind.PERCENT_BASIS_POINTS, 1_500,
                30_000L, true, now.minusSeconds(60), now.plusSeconds(3_600), 100_000, 20, 2);
        createVoucher("A-CATALOG", OrderVoucher.Kind.FIXED_VND, 10_000,
                null, true, now.minusSeconds(60), now.plusSeconds(3_600), 50_000, 20, 2);
        em.flush();
        long voucherCountersBefore = number("select coalesce(sum(reserved_count + redeemed_count), 0) from vouchers");
        long usageRowsBefore = number("select count(*) from voucher_user_usage");
        long claimsBefore = number("select count(*) from voucher_claims");

        mvc.perform(get("/api/vouchers").header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.size").value(10))
                .andExpect(jsonPath("$.data.number").value(0))
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].code").value("A-CATALOG"))
                .andExpect(jsonPath("$.data.content[0].discountType").value("FIXED_VND"))
                .andExpect(jsonPath("$.data.content[0].discountValue").value(10_000))
                .andExpect(jsonPath("$.data.content[0].minimumEligibleMerchandiseSpendVnd").value(50_000))
                .andExpect(jsonPath("$.data.content[0].availability").value("POTENTIALLY_AVAILABLE"))
                .andExpect(jsonPath("$.data.content[0].minimumSpendEvaluated").value(false))
                .andExpect(jsonPath("$.data.content[0].maximumDiscountVnd").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].id").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].reservedCount").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].redeemedCount").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].claimId").doesNotExist())
                .andExpect(jsonPath("$.data.content[1].maximumDiscountVnd").value(30_000));

        em.clear();
        assertThat(number("select coalesce(sum(reserved_count + redeemed_count), 0) from vouchers"))
                .isEqualTo(voucherCountersBefore);
        assertThat(number("select count(*) from voucher_user_usage")).isEqualTo(usageRowsBefore);
        assertThat(number("select count(*) from voucher_claims")).isEqualTo(claimsBefore);
    }

    @Test void paginationBoundsAndAuthenticationAreEnforced() throws Exception {
        User owner = createUser("paging");
        mvc.perform(get("/api/vouchers")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/vouchers").header("Authorization", bearer(owner)).param("size", "50"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.size").value(50));
        for (String[] pair : new String[][]{{"page", "-1"}, {"size", "0"}, {"size", "51"}, {"size", "bad"}}) {
            mvc.perform(get("/api/vouchers").header("Authorization", bearer(owner))
                            .param(pair[0], pair[1]))
                    .andExpect(status().isBadRequest());
        }
    }

    @Test void catalogReportsCampaignAndQuotaStatesUsingOnlyOwnersUsage() throws Exception {
        User owner = createUser("quota-owner");
        User other = createUser("quota-other");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        createVoucher("01-EXPIRED", OrderVoucher.Kind.FIXED_VND, 1, null, true,
                now.minusSeconds(120), now.minusSeconds(60), 0, 10, 1);
        createVoucher("02-INACTIVE", OrderVoucher.Kind.FIXED_VND, 1, null, false,
                now.minusSeconds(60), now.plusSeconds(120), 0, 10, 1);
        createVoucher("03-FUTURE", OrderVoucher.Kind.FIXED_VND, 1, null, true,
                now.plusSeconds(60), now.plusSeconds(180), 0, 10, 1);
        VoucherJpaEntity total = createVoucher("04-TOTAL", OrderVoucher.Kind.FIXED_VND, 1, null, true,
                now.minusSeconds(60), now.plusSeconds(240), 0, 1, 1);
        total.reserveOne();
        VoucherJpaEntity perUser = createVoucher("05-USER", OrderVoucher.Kind.FIXED_VND, 1, null, true,
                now.minusSeconds(60), now.plusSeconds(300), 0, 10, 1);
        perUser.reserveOne();
        var ownerUsage = new VoucherUserUsageJpaEntity(perUser.getId(), owner.getId());
        ownerUsage.reserveOne();
        em.persist(ownerUsage);
        VoucherJpaEntity otherOnly = createVoucher("06-OTHER", OrderVoucher.Kind.FIXED_VND, 1, null, true,
                now.minusSeconds(60), now.plusSeconds(360), 0, 10, 1);
        otherOnly.reserveOne();
        var otherUsage = new VoucherUserUsageJpaEntity(otherOnly.getId(), other.getId());
        otherUsage.reserveOne();
        em.persist(otherUsage);
        em.flush();

        mvc.perform(get("/api/vouchers").header("Authorization", bearer(owner)).param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].availability").value("EXPIRED"))
                .andExpect(jsonPath("$.data.content[1].availability").value("INACTIVE"))
                .andExpect(jsonPath("$.data.content[2].availability").value("NOT_STARTED"))
                .andExpect(jsonPath("$.data.content[3].availability").value("TOTAL_QUOTA_EXHAUSTED"))
                .andExpect(jsonPath("$.data.content[4].availability").value("USER_QUOTA_EXHAUSTED"))
                .andExpect(jsonPath("$.data.content[5].availability").value("POTENTIALLY_AVAILABLE"));
    }

    private VoucherJpaEntity createVoucher(String code, OrderVoucher.Kind kind, long value, Long cap,
                                            boolean active, Instant from, Instant until, long minimum,
                                            long totalLimit, long userLimit) {
        var voucher = new Voucher(code, kind, value,
                cap == null ? OptionalLong.empty() : OptionalLong.of(cap), active, from, until, minimum,
                OptionalLong.of(totalLimit), OptionalLong.of(userLimit));
        var row = VoucherJpaEntity.fromDefinition(voucher);
        em.persist(row);
        em.flush();
        return row;
    }

    private User createUser(String label) {
        return users.saveAndFlush(User.builder().fullName("Voucher Catalog Test")
                .email(label + "-" + UUID.randomUUID() + "@example.test")
                .passwordHash("test-hash").role(Role.USER).isActive(true).build());
    }

    private String bearer(User user) {
        return "Bearer " + jwt.generateAccessToken(userDetails.loadUserByUsername(user.getEmail()));
    }

    private long number(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }
}
