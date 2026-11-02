package com.artacademy.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import com.drawingcourse.backend.repository.VoucherCatalogProjection;
import com.drawingcourse.backend.repository.VoucherRepository;
import com.drawingcourse.backend.service.VoucherCatalogAvailability;
import com.drawingcourse.backend.service.VoucherCatalogAvailabilityEvaluator;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.OptionalLong;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Guarded MySQL 8 verification for the authenticated, read-only Voucher catalog.
 * Every fixture write belongs to the test transaction and is rolled back.
 */
@SpringBootTest(classes = ArtAcademyApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles({"test", "mysql-it"})
@ContextConfiguration(initializers = MySqlSchemaCompatibilityIT.TargetGuard.class)
@TestPropertySource(properties = "app.voucher.api-enabled=true")
@Transactional
class MySqlVoucherCatalogCompatibilityIT {
    private static final String EXPECTED_DATABASE = "drawing_course_it";

    @Autowired private MockMvc mvc;
    @Autowired private UserRepository users;
    @Autowired private CustomUserDetailsService userDetails;
    @Autowired private JwtService jwt;
    @Autowired private EntityManager entityManager;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private VoucherRepository vouchers;

    @BeforeEach
    void requireGuardedV1ThroughV9Target() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(EXPECTED_DATABASE);
        assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(3306);
        assertThat(jdbc.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                 WHERE success=TRUE AND version IN ('1','2','3','4','5','6','7','8','9')
                """, Integer.class)).isEqualTo(9);
        assertThat(jdbc.queryForObject("""
                SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history WHERE success=TRUE
                """, Long.class)).isEqualTo(9L);
    }

    @Test
    void projectionApiPaginationOrderingAndResponseRemainReadOnly() throws Exception {
        long baseline = count("SELECT COUNT(*) FROM vouchers");
        assertThat(baseline).as("isolated integration database must leave room for one complete page")
                .isLessThanOrEqualTo(47);
        User owner = createUser("catalog-owner");
        Instant firstUntil = fixtureDeadlineBeforeExistingRows();
        String prefix = "MYSQL-CATALOG-" + shortId();
        VoucherJpaEntity first = createVoucher(prefix + "-A", OrderVoucher.Kind.FIXED_VND,
                10_000, null, true, firstUntil.minusSeconds(86_400), firstUntil, 50_000, 20, 2);
        createVoucher(prefix + "-B", OrderVoucher.Kind.PERCENT_BASIS_POINTS,
                1_500, 30_000L, true, firstUntil.minusSeconds(86_400), firstUntil, 100_000, 20, 2);
        createVoucher(prefix + "-C", OrderVoucher.Kind.FIXED_VND,
                5_000, null, true, firstUntil.minusSeconds(86_400), firstUntil.plusSeconds(1), 0, 20, 2);
        entityManager.flush();

        long voucherUseBefore = count("SELECT reserved_count + redeemed_count FROM vouchers WHERE id=" + first.getId());
        long usageRowsBefore = count("SELECT COUNT(*) FROM voucher_user_usage");
        long claimsBefore = count("SELECT COUNT(*) FROM voucher_claims");

        var page = mvc.perform(get("/api/vouchers")
                        .header("Authorization", bearer(owner)).param("page", "0").param("size", "50"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalElements").value(baseline + 3))
                .andExpect(jsonPath("$.data.content[0].code").value(prefix + "-A"))
                .andExpect(jsonPath("$.data.content[1].code").value(prefix + "-B"))
                .andExpect(jsonPath("$.data.content[2].code").value(prefix + "-C"))
                .andExpect(jsonPath("$.data.content[0].id").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].definitionVersion").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].reservedCount").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].redeemedCount").doesNotExist())
                .andExpect(jsonPath("$.data.content[0].claimId").doesNotExist())
                .andReturn().getResponse().getContentAsString();

        List<String> codes = JsonPath.parse(page).read("$.data.content[*].code");
        assertThat(codes).doesNotHaveDuplicates();
        assertThat(codes).containsOnlyOnce(prefix + "-A", prefix + "-B", prefix + "-C");

        long expectedTotal = baseline + 3;
        mvc.perform(get("/api/vouchers")
                        .header("Authorization", bearer(owner)).param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(expectedTotal))
                .andExpect(jsonPath("$.data.totalPages").value((expectedTotal + 1) / 2))
                .andExpect(jsonPath("$.data.content[0].code").value(prefix + "-A"))
                .andExpect(jsonPath("$.data.content[1].code").value(prefix + "-B"));
        mvc.perform(get("/api/vouchers")
                        .header("Authorization", bearer(owner)).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content[0].code").value(prefix + "-C"));

        entityManager.flush();
        entityManager.clear();
        assertThat(count("SELECT reserved_count + redeemed_count FROM vouchers WHERE id=" + first.getId()))
                .isEqualTo(voucherUseBefore);
        assertThat(count("SELECT COUNT(*) FROM voucher_user_usage")).isEqualTo(usageRowsBefore);
        assertThat(count("SELECT COUNT(*) FROM voucher_claims")).isEqualTo(claimsBefore);
    }

    @Test
    void leftJoinUsesOnlyAuthenticatedUsersQuotaAndUnauthenticatedAccessIsRejected() throws Exception {
        assertThat(count("SELECT COUNT(*) FROM vouchers"))
                .as("isolated integration database must leave room for the quota fixture")
                .isLessThanOrEqualTo(49);
        User exhaustedUser = createUser("quota-exhausted");
        User availableUser = createUser("quota-available");
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Instant until = now.plusSeconds(3_600);
        String code = "MYSQL-QUOTA-" + shortId();
        VoucherJpaEntity voucher = createVoucher(code, OrderVoucher.Kind.FIXED_VND,
                1_000, null, true, now.minusSeconds(60), until, 0, 10, 1);
        voucher.reserveOne();
        var usage = new VoucherUserUsageJpaEntity(voucher.getId(), exhaustedUser.getId());
        usage.reserveOne();
        entityManager.persist(usage);
        entityManager.flush();

        long voucherUseBefore = count("SELECT reserved_count + redeemed_count FROM vouchers WHERE id=" + voucher.getId());
        long ownerUseBefore = count("""
                SELECT reserved_count + redeemed_count FROM voucher_user_usage
                 WHERE voucher_id=%d AND user_id=%d
                """.formatted(voucher.getId(), exhaustedUser.getId()));
        long claimsBefore = count("SELECT COUNT(*) FROM voucher_claims");

        String exhaustedBody = mvc.perform(get("/api/vouchers")
                        .header("Authorization", bearer(exhaustedUser)).param("size", "50"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String availableBody = mvc.perform(get("/api/vouchers")
                        .header("Authorization", bearer(availableUser)).param("size", "50"))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/vouchers")).andExpect(status().isUnauthorized());

        assertThat(availability(exhaustedBody, code)).containsExactly("USER_QUOTA_EXHAUSTED");
        assertThat(availability(availableBody, code)).containsExactly("POTENTIALLY_AVAILABLE");
        assertThat(countOccurrences(exhaustedBody, code)).isEqualTo(1);
        assertThat(countOccurrences(availableBody, code)).isEqualTo(1);

        entityManager.flush();
        entityManager.clear();
        assertThat(count("SELECT reserved_count + redeemed_count FROM vouchers WHERE id=" + voucher.getId()))
                .isEqualTo(voucherUseBefore);
        assertThat(count("""
                SELECT reserved_count + redeemed_count FROM voucher_user_usage
                 WHERE voucher_id=%d AND user_id=%d
                """.formatted(voucher.getId(), exhaustedUser.getId()))).isEqualTo(ownerUseBefore);
        assertThat(count("SELECT COUNT(*) FROM voucher_claims")).isEqualTo(claimsBefore);
    }

    @Test
    void mysqlDatetimeRoundTripPreservesExclusiveValidUntilBoundary() {
        assertThat(count("SELECT COUNT(*) FROM vouchers"))
                .as("isolated integration database must leave room for the boundary fixture")
                .isLessThanOrEqualTo(49);
        User owner = createUser("boundary-owner");
        Instant until = Instant.parse("2031-07-08T09:10:11.123456Z");
        VoucherJpaEntity voucher = createVoucher("MYSQL-BOUNDARY-" + shortId(),
                OrderVoucher.Kind.FIXED_VND, 1_000, null, true,
                until.minusSeconds(86_400), until, 0, 10, 2);
        entityManager.flush();
        entityManager.clear();

        VoucherCatalogProjection row = vouchers.findCatalogForUser(owner.getId(), PageRequest.of(0, 50))
                .stream().filter(candidate -> candidate.getCode().equals(voucher.getCode())).findFirst().orElseThrow();
        assertThat(row.getValidUntil()).isEqualTo(until);
        var input = new VoucherCatalogAvailabilityEvaluator.Input(row.isActive(), row.getValidFrom(),
                row.getValidUntil(), optional(row.getTotalUsageLimit()), optional(row.getPerUserUsageLimit()),
                row.getReservedCount(), row.getRedeemedCount(), row.getUserReservedCount(), row.getUserRedeemedCount());
        assertThat(new VoucherCatalogAvailabilityEvaluator().evaluate(input, until).availability())
                .isEqualTo(VoucherCatalogAvailability.EXPIRED);
    }

    private VoucherJpaEntity createVoucher(String code, OrderVoucher.Kind kind, long value, Long cap,
                                            boolean active, Instant from, Instant until, long minimum,
                                            long totalLimit, long perUserLimit) {
        Voucher definition = new Voucher(code, kind, value,
                cap == null ? OptionalLong.empty() : OptionalLong.of(cap), active,
                from.truncatedTo(ChronoUnit.MICROS), until.truncatedTo(ChronoUnit.MICROS), minimum,
                OptionalLong.of(totalLimit), OptionalLong.of(perUserLimit));
        VoucherJpaEntity row = VoucherJpaEntity.fromDefinition(definition);
        entityManager.persist(row);
        entityManager.flush();
        return row;
    }

    private User createUser(String label) {
        return users.saveAndFlush(User.builder().fullName("MySQL Voucher Catalog Test")
                .email(label + "-" + UUID.randomUUID() + "@example.test")
                .passwordHash("test-only").role(Role.USER).isActive(true).build());
    }

    private Instant fixtureDeadlineBeforeExistingRows() {
        LocalDateTime earliest = jdbc.queryForObject("SELECT MIN(valid_until) FROM vouchers", LocalDateTime.class);
        Instant result = earliest == null
                ? Instant.parse("2000-01-02T00:00:00Z")
                : earliest.toInstant(ZoneOffset.UTC).minusSeconds(2);
        assertThat(result).as("fixture deadline must remain inside MySQL DATETIME range")
                .isAfter(Instant.parse("1000-01-02T00:00:00Z"));
        return result.truncatedTo(ChronoUnit.MICROS);
    }

    private String bearer(User user) {
        return "Bearer " + jwt.generateAccessToken(userDetails.loadUserByUsername(user.getEmail()));
    }

    private List<String> availability(String body, String code) {
        return JsonPath.parse(body).read("$.data.content[?(@.code == '" + code + "')].availability");
    }

    private int countOccurrences(String body, String code) {
        List<String> matches = JsonPath.parse(body).read("$.data.content[?(@.code == '" + code + "')].code");
        return matches.size();
    }

    private long count(String sql) {
        return jdbc.queryForObject(sql, Long.class);
    }

    private static OptionalLong optional(Long value) {
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    private static String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
