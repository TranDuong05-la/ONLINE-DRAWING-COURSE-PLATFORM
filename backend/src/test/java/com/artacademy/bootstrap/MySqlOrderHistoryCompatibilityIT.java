package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.repository.UserRepository;
import com.artacademy.service.CustomUserDetailsService;
import com.artacademy.service.JwtService;
import com.drawingcourse.backend.entity.OrderUtcInstantConverter;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.web.servlet.MockMvc;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Opt-in HTTP and repository-projection compatibility tests for Order History on real MySQL.
 * The shared target guard refuses to start unless the caller explicitly acknowledges the
 * isolated {@code drawing_course_it} database. Invoke only with {@code mvn -Pmysql-it verify}.
 */
@SpringBootTest(classes = ArtAcademyApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles({"test", "mysql-it"})
@ContextConfiguration(initializers = MySqlSchemaCompatibilityIT.TargetGuard.class)
class MySqlOrderHistoryCompatibilityIT {
    private static final String EXPECTED_DATABASE = "drawing_course_it";

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ConfigurableEnvironment environment;
    @Autowired private UserRepository users;
    @Autowired private CustomUserDetailsService userDetails;
    @Autowired private JwtService jwt;

    private final List<Integer> orderIds = new ArrayList<>();
    private final List<Integer> userIds = new ArrayList<>();
    private final OrderUtcInstantConverter instants = new OrderUtcInstantConverter();

    @BeforeEach
    void requireGuardedMysqlTarget() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo(EXPECTED_DATABASE);
        assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(3306);
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("spring.flyway.clean-disabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.flyway.validate-on-migrate", Boolean.class)).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flyway_schema_history WHERE success=TRUE AND version='3'",
                Integer.class)).isEqualTo(1);
    }

    @AfterEach
    void deleteOnlyFixturesOwnedByThisTest() {
        for (int index = orderIds.size() - 1; index >= 0; index--) {
            Integer orderId = orderIds.get(index);
            jdbc.update("DELETE FROM order_shipping_addresses WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM order_items WHERE order_id=?", orderId);
            jdbc.update("DELETE FROM orders WHERE id=?", orderId);
        }
        for (int index = userIds.size() - 1; index >= 0; index--)
            jdbc.update("DELETE FROM users WHERE id=?", userIds.get(index));
        orderIds.clear();
        userIds.clear();
    }

    @Test
    void ownerListUsesMysqlProjectionDeterministicOrderingPaginationAndScalarCounts() throws Exception {
        User owner = user("list-owner");
        User foreign = user("list-foreign");
        Instant tied = Instant.parse("2026-10-20T04:05:06.123456Z");

        int first = order(owner, tied, tied.plusSeconds(1_800), new Money(250_000, 200_000, 10_000, 2_500, 242_500),
                new Voucher("MYSQL-HISTORY-10K", "FIXED_VND", 10_000, 10_000));
        item(first, 0, "PRODUCT", 31, "Historical brush set", 2, 100_000, true, 200_000, 10_000, 190_000);
        item(first, 1, "COURSE", 41, "Historical course", 1, 50_000, false, 50_000, 0, 50_000);

        int second = order(owner, tied, null, Money.ZERO, null);
        int older = order(owner, tied.minusSeconds(1), tied.plusSeconds(1_799),
                new Money(75_000, 75_000, 0, 0, 75_000), null);
        int foreignOrder = order(foreign, tied.plusSeconds(60), tied.plusSeconds(1_860),
                new Money(999_999, 999_999, 0, 0, 999_999), null);

        mvc.perform(get("/api/orders").header("Authorization", bearer(owner))
                        .param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[0].orderId").value(second))
                .andExpect(jsonPath("$.data.content[0].itemCount").value(0))
                .andExpect(jsonPath("$.data.content[0].currency").value("VND"))
                .andExpect(jsonPath("$.data.content[0].paymentExpiresAt").value((Object) null))
                .andExpect(jsonPath("$.data.content[0].totalVnd").value(0))
                .andExpect(jsonPath("$.data.content[1].orderId").value(first))
                .andExpect(jsonPath("$.data.content[1].itemCount").value(2))
                .andExpect(jsonPath("$.data.content[1].merchandiseSubtotalVnd").value(250_000))
                .andExpect(jsonPath("$.data.content[1].eligibleSubtotalVnd").value(200_000))
                .andExpect(jsonPath("$.data.content[1].discountVnd").value(10_000))
                .andExpect(jsonPath("$.data.content[1].shippingFeeVnd").value(2_500))
                .andExpect(jsonPath("$.data.content[1].totalVnd").value(242_500))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.size").value(2))
                .andExpect(jsonPath("$.data.number").value(0));

        mvc.perform(get("/api/orders").header("Authorization", bearer(owner))
                        .param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].orderId").value(older))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2));

        assertThat(foreignOrder).isNotIn(first, second, older);
    }

    @Test
    void ownerDetailReturnsPersistedMysqlSnapshotsAndDoesNotMutateOrderState() throws Exception {
        User owner = user("detail-owner");
        Instant created = Instant.parse("2026-10-21T01:02:03.123456Z");
        int orderId = order(owner, created, created.plusSeconds(1_800),
                new Money(250_000, 200_000, 10_000, 2_500, 242_500),
                new Voucher("MYSQL-SNAPSHOT-10K", "FIXED_VND", 10_000, 10_000));
        item(orderId, 0, "PRODUCT", 31, "Historical brush set", 2, 100_000, true, 200_000, 10_000, 190_000);
        item(orderId, 1, "COURSE", 41, "Historical course", 1, 50_000, false, 50_000, 0, 50_000);
        address(orderId);
        Map<String, Object> before = state(orderId);

        mvc.perform(get("/api/orders").header("Authorization", bearer(owner)))
                .andExpect(status().isOk());
        mvc.perform(get("/api/orders/{id}", orderId).header("Authorization", bearer(owner)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.orderId").value(orderId))
                .andExpect(jsonPath("$.data.status").value("PENDING_PAYMENT"))
                .andExpect(jsonPath("$.data.paymentStatus").value("CREATED"))
                .andExpect(jsonPath("$.data.currency").value("VND"))
                .andExpect(jsonPath("$.data.paymentExpiresAt").value("2026-10-21T01:32:03.123456Z"))
                .andExpect(jsonPath("$.data.merchandiseSubtotalVnd").value(250_000))
                .andExpect(jsonPath("$.data.eligibleSubtotalVnd").value(200_000))
                .andExpect(jsonPath("$.data.discountVnd").value(10_000))
                .andExpect(jsonPath("$.data.shippingFeeVnd").value(2_500))
                .andExpect(jsonPath("$.data.totalVnd").value(242_500))
                .andExpect(jsonPath("$.data.voucher.code").value("MYSQL-SNAPSHOT-10K"))
                .andExpect(jsonPath("$.data.voucher.kind").value("FIXED_VND"))
                .andExpect(jsonPath("$.data.voucher.value").value(10_000))
                .andExpect(jsonPath("$.data.voucher.maximumDiscountVnd").value(10_000))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andExpect(jsonPath("$.data.items[0].linePosition").value(0))
                .andExpect(jsonPath("$.data.items[0].itemId").value(31))
                .andExpect(jsonPath("$.data.items[0].nameSnapshot").value("Historical brush set"))
                .andExpect(jsonPath("$.data.items[0].quantity").value(2))
                .andExpect(jsonPath("$.data.items[0].unitPriceVnd").value(100_000))
                .andExpect(jsonPath("$.data.items[0].voucherEligible").value(true))
                .andExpect(jsonPath("$.data.items[0].grossAmountVnd").value(200_000))
                .andExpect(jsonPath("$.data.items[0].allocatedDiscountVnd").value(10_000))
                .andExpect(jsonPath("$.data.items[0].netAmountVnd").value(190_000))
                .andExpect(jsonPath("$.data.items[1].linePosition").value(1))
                .andExpect(jsonPath("$.data.items[1].itemId").value(41))
                .andExpect(jsonPath("$.data.items[1].nameSnapshot").value("Historical course"))
                .andExpect(jsonPath("$.data.shippingAddress.recipient").value("MySQL historical recipient"))
                .andExpect(jsonPath("$.data.shippingAddress.phone").value("0900000000"))
                .andExpect(jsonPath("$.data.shippingAddress.addressLine").value("Historical street"))
                .andExpect(jsonPath("$.data.shippingAddress.locality").value("Historical locality"))
                .andExpect(jsonPath("$.data.shippingAddress.postalCode").value("700000"))
                .andExpect(jsonPath("$.data.shippingAddress.countryCode").value("VN"))
                .andExpect(jsonPath("$.data.userId").doesNotExist());

        assertThat(state(orderId)).isEqualTo(before);
    }

    @Test
    void foreignAndMissingDetailHaveTheSameNotFoundContract() throws Exception {
        User owner = user("not-found-owner");
        User foreign = user("not-found-foreign");
        int foreignOrder = order(foreign, Instant.parse("2026-10-22T00:00:00Z"), null, Money.ZERO, null);

        mvc.perform(get("/api/orders/{id}", foreignOrder).header("Authorization", bearer(owner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Order not found"));
        mvc.perform(get("/api/orders/{id}", Integer.MAX_VALUE).header("Authorization", bearer(owner)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Order not found"));
    }

    @Test
    void invalidPaginationAndOrderIdsAreRejectedOnMysqlBackedApi() throws Exception {
        User owner = user("invalid-input-owner");
        for (String[] parameter : List.of(new String[]{"page", "-1"}, new String[]{"size", "0"},
                new String[]{"size", "51"}, new String[]{"size", "not-a-number"})) {
            mvc.perform(get("/api/orders").header("Authorization", bearer(owner))
                            .param(parameter[0], parameter[1]))
                    .andExpect(status().isBadRequest());
        }
        mvc.perform(get("/api/orders/0").header("Authorization", bearer(owner)))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/orders/not-a-number").header("Authorization", bearer(owner)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unauthenticatedHistoryRequestsAreRejectedBeforeMysqlQueries() throws Exception {
        mvc.perform(get("/api/orders")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/orders/1")).andExpect(status().isUnauthorized());
    }

    private User user(String label) {
        User user = users.saveAndFlush(User.builder()
                .fullName("MySQL Order History Test")
                .email("mysql-order-history-" + label + "-" + UUID.randomUUID() + "@example.test")
                .passwordHash("test-only")
                .role(Role.USER)
                .isActive(true)
                .build());
        userIds.add(user.getId());
        return user;
    }

    private String bearer(User user) {
        return "Bearer " + jwt.generateAccessToken(userDetails.loadUserByUsername(user.getEmail()));
    }

    private int order(User owner, Instant createdAt, Instant deadline, Money money, Voucher voucher) {
        var keys = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO orders(version,user_id,currency,status,payment_status,
                      merchandise_subtotal_vnd,eligible_subtotal_vnd,discount_vnd,shipping_fee_vnd,total_vnd,
                      voucher_code,voucher_kind,voucher_value,voucher_max_discount_vnd,
                      payment_expires_at,created_at,updated_at)
                    VALUES (0,?,'VND','PENDING_PAYMENT','CREATED',?,?,?,?,?,?,?,?,?,?,?,?)
                    """, Statement.RETURN_GENERATED_KEYS);
            int index = 1;
            statement.setInt(index++, owner.getId());
            statement.setLong(index++, money.merchandiseSubtotalVnd());
            statement.setLong(index++, money.eligibleSubtotalVnd());
            statement.setLong(index++, money.discountVnd());
            statement.setLong(index++, money.shippingFeeVnd());
            statement.setLong(index++, money.totalVnd());
            setNullableString(statement, index++, voucher == null ? null : voucher.code());
            setNullableString(statement, index++, voucher == null ? null : voucher.kind());
            setNullableLong(statement, index++, voucher == null ? null : voucher.value());
            setNullableLong(statement, index++, voucher == null ? null : voucher.maximumDiscountVnd());
            if (deadline == null) statement.setNull(index++, Types.TIMESTAMP);
            else statement.setObject(index++, instants.convertToDatabaseColumn(deadline));
            statement.setObject(index++, instants.convertToDatabaseColumn(createdAt));
            statement.setObject(index, instants.convertToDatabaseColumn(createdAt));
            return statement;
        }, keys);
        int id = keys.getKey().intValue();
        orderIds.add(id);
        return id;
    }

    private void item(int orderId, int position, String type, int itemId, String name, int quantity,
                      long unitPrice, boolean voucherEligible, long gross, long discount, long net) {
        jdbc.update("""
                INSERT INTO order_items(order_id,line_position,item_type,item_id,name_snapshot,quantity,
                  unit_price_vnd,voucher_eligible,gross_amount_vnd,allocated_discount_vnd,net_amount_vnd)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, orderId, position, type, itemId, name, quantity, unitPrice, voucherEligible, gross, discount, net);
    }

    private void address(int orderId) {
        jdbc.update("""
                INSERT INTO order_shipping_addresses(order_id,recipient,phone,address_line,locality,postal_code,country_code)
                VALUES (?,'MySQL historical recipient','0900000000','Historical street','Historical locality','700000','VN')
                """, orderId);
    }

    private Map<String, Object> state(int orderId) {
        return jdbc.queryForMap("""
                SELECT version,status,payment_status,updated_at,merchandise_subtotal_vnd,
                  eligible_subtotal_vnd,discount_vnd,shipping_fee_vnd,total_vnd
                FROM orders WHERE id=?
                """, orderId);
    }

    private static void setNullableString(PreparedStatement statement, int index, String value)
            throws java.sql.SQLException {
        if (value == null) statement.setNull(index, Types.VARCHAR);
        else statement.setString(index, value);
    }

    private static void setNullableLong(PreparedStatement statement, int index, Long value)
            throws java.sql.SQLException {
        if (value == null) statement.setNull(index, Types.BIGINT);
        else statement.setLong(index, value);
    }

    private record Money(long merchandiseSubtotalVnd, long eligibleSubtotalVnd, long discountVnd,
                         long shippingFeeVnd, long totalVnd) {
        private static final Money ZERO = new Money(0, 0, 0, 0, 0);
    }

    private record Voucher(String code, String kind, long value, long maximumDiscountVnd) { }
}
