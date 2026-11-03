package com.artacademy.bootstrap;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CourseV13MySqlTargetGuardTest {
    private static final String USER = "integration-test-user";
    private static final String PASSWORD = "synthetic-test-password";

    @Test
    void acceptsOnlyExplicitExactV13DrawingCourseItTargetWithAllFlagsOff() {
        assertThatCode(() -> CourseV13MySqlTargetGuard.validateForTest(environment(), variables(), true))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingOptInAcknowledgementOrCredentials() {
        assertRejected(environment(), variables(), false);
        Map<String, String> missingDatabaseAck = variables();
        missingDatabaseAck.remove("MYSQL_IT_DATABASE_ACK");
        assertRejected(environment(), missingDatabaseAck, true);
        Map<String, String> missingMigrationAck = variables();
        missingMigrationAck.remove("MYSQL_COURSE_V13_MIGRATION_ACK");
        assertRejected(environment(), missingMigrationAck, true);
        Map<String, String> missingPassword = variables();
        missingPassword.remove("MYSQL_IT_PASSWORD");
        assertRejected(environment(), missingPassword, true);
    }

    @Test
    void rejectsDevelopmentSmokeOtherHostsAndOtherDatabaseNames() {
        for (String url : new String[] {
                "jdbc:mysql://127.0.0.1:3306/drawing_course_dev",
                "jdbc:mysql://127.0.0.1:3306/drawing_course_smoke",
                "jdbc:mysql://localhost:3306/drawing_course_it",
                "jdbc:mysql://192.168.1.10:3306/drawing_course_it",
                "jdbc:h2:file:./demo-v2"
        }) {
            Map<String, String> variables = variables();
            variables.put("MYSQL_IT_URL", url);
            MockEnvironment env = environment();
            env.setProperty("spring.datasource.url", url);
            assertRejected(env, variables, true);
        }
        Map<String, String> wrongDbAck = variables();
        wrongDbAck.put("MYSQL_IT_DATABASE_ACK", "drawing_course_dev");
        assertRejected(environment(), wrongDbAck, true);
    }

    @Test
    void rejectsWrongFlywayTargetOrEnabledFeatureWithoutBehaviorTestOptIn() {
        MockEnvironment wrongTarget = environment();
        wrongTarget.setProperty("spring.flyway.target", "10");
        assertRejected(wrongTarget, variables(), true);
        MockEnvironment checkoutEnabled = environment();
        checkoutEnabled.setProperty("app.checkout.course-only.enabled", "true");
        assertRejected(checkoutEnabled, variables(), true);
    }

    @Test
    void behaviorSuiteMayEnableFlagsOnlyWithItsTestOnlyOptIn() {
        MockEnvironment behavior = environment();
        behavior.setProperty("mysql.course.v13.behavior.test-opt-in", "true");
        behavior.setProperty("app.payment.settlement.worker.enabled", "true");
        assertThatCode(() -> CourseV13MySqlTargetGuard.validateForTest(behavior, variables(), true))
                .doesNotThrowAnyException();
    }

    private static void assertRejected(MockEnvironment environment, Map<String, String> variables,
                                       boolean optIn) {
        assertThatThrownBy(() -> CourseV13MySqlTargetGuard.validateForTest(environment, variables, optIn))
                .isInstanceOf(IllegalStateException.class);
    }

    private static MockEnvironment environment() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", CourseV13MySqlTargetGuard.URL)
                .withProperty("spring.datasource.username", USER)
                .withProperty("spring.datasource.password", PASSWORD)
                .withProperty("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver")
                .withProperty("spring.jpa.hibernate.ddl-auto", "validate")
                .withProperty("spring.flyway.enabled", "true")
                .withProperty("spring.flyway.validate-on-migrate", "true")
                .withProperty("spring.flyway.clean-disabled", "true")
                .withProperty("spring.flyway.baseline-on-migrate", "false")
                .withProperty("spring.flyway.target", "13");
        environment.setActiveProfiles("mysql-course-v13-it");
        return environment;
    }

    private static Map<String, String> variables() {
        Map<String, String> values = new HashMap<>();
        values.put("MYSQL_IT_DATABASE_ACK", "drawing_course_it");
        values.put("MYSQL_COURSE_V13_MIGRATION_ACK", CourseV13MySqlTargetGuard.MIGRATION_ACK);
        values.put("MYSQL_IT_URL", CourseV13MySqlTargetGuard.URL);
        values.put("MYSQL_IT_USERNAME", USER);
        values.put("MYSQL_IT_PASSWORD", PASSWORD);
        return values;
    }
}
