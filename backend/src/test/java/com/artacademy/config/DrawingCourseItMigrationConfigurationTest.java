package com.artacademy.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.core.env.PropertySource;
import org.springframework.boot.env.YamlPropertySourceLoader;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class DrawingCourseItMigrationConfigurationTest {
    private static final Map<String, String> VARS = Map.of(
            "MYSQL_IT_DATABASE_ACK", "drawing_course_it",
            "MYSQL_IT_MIGRATION_ACK", "apply-v14-v17-to-drawing_course_it",
            "MYSQL_IT_USERNAME", "local-user",
            "MYSQL_IT_PASSWORD", "synthetic-password");

    @Test
    void acceptsOnlyExplicitPinnedV17MigrationConfiguration() {
        assertThatCode(() -> DrawingCourseItMigrationConfiguration.validate(environment(), VARS))
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsMissingAcknowledgementWrongTargetWrongVersionOrEnabledWorkers() {
        assertThatThrownBy(() -> DrawingCourseItMigrationConfiguration.validate(environment(), Map.of()))
                .isInstanceOf(IllegalStateException.class);
        var wrongTarget = environment().withProperty("spring.flyway.url", "jdbc:mysql://localhost/db");
        assertThatThrownBy(() -> DrawingCourseItMigrationConfiguration.validate(wrongTarget, VARS))
                .isInstanceOf(IllegalStateException.class);
        var wrongVersion = environment().withProperty("spring.flyway.target", "18");
        assertThatThrownBy(() -> DrawingCourseItMigrationConfiguration.validate(wrongVersion, VARS))
                .isInstanceOf(IllegalStateException.class);
        var workerEnabled = environment().withProperty("app.payment.settlement.worker.enabled", "true");
        assertThatThrownBy(() -> DrawingCourseItMigrationConfiguration.validate(workerEnabled, VARS))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void migrationProfileResolvesSharedPlaceholdersWithoutRuntimeSecretsOrSmtp() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> base = loader.load("base", new ClassPathResource("application.yaml"));
        List<PropertySource<?>> migration = loader.load("migration", new ClassPathResource("application-mysql-demo-migrate.yml"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("MYSQL_IT_MIGRATION_JWT_SECRET", "synthetic-ephemeral-migration-test-key-long-enough");
        migration.forEach(environment.getPropertySources()::addFirst);
        base.forEach(environment.getPropertySources()::addLast);

        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:mysql://127.0.0.1:3306/drawing_course_it");
        assertThat(environment.getProperty("spring.mail.host")).isEqualTo("localhost");
        assertThat(environment.getProperty("spring.mail.username")).isEmpty();
        assertThat(environment.getProperty("spring.mail.password")).isEmpty();
        assertThat(environment.getProperty("spring.mail.test-connection", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.mail.properties.mail.smtp.auth", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.mail.properties.mail.smtp.starttls.enable", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.main.lazy-initialization", Boolean.class)).isTrue();
        assertThat(environment.getProperty("app.jwt.secret"))
                .isEqualTo("synthetic-ephemeral-migration-test-key-long-enough");
        assertThat(environment.getProperty("spring.flyway.target")).isEqualTo("17");
        assertThat(environment.getProperty("spring.flyway.clean-disabled", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.flyway.validate-on-migrate", Boolean.class)).isTrue();
        assertThat(environment.getProperty("spring.flyway.baseline-on-migrate", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.flyway.out-of-order", Boolean.class)).isFalse();
    }

    private static MockEnvironment environment() {
        MockEnvironment environment = new MockEnvironment()
                .withProperty("spring.datasource.url", DrawingCourseItMigrationConfiguration.TARGET_URL)
                .withProperty("spring.datasource.username", "local-user")
                .withProperty("spring.datasource.password", "synthetic-password")
                .withProperty("spring.flyway.url", DrawingCourseItMigrationConfiguration.TARGET_URL)
                .withProperty("spring.flyway.user", "local-user")
                .withProperty("spring.flyway.password", "synthetic-password")
                .withProperty("spring.flyway.enabled", "true")
                .withProperty("spring.flyway.clean-disabled", "true")
                .withProperty("spring.flyway.validate-on-migrate", "true")
                .withProperty("spring.flyway.baseline-on-migrate", "false")
                .withProperty("spring.flyway.out-of-order", "false")
                .withProperty("spring.flyway.target", "17")
                .withProperty("spring.main.web-application-type", "none")
                .withProperty("app.cart.api-enabled", "false")
                .withProperty("app.cart.course-only.api-enabled", "false")
                .withProperty("app.checkout.product.enabled", "false")
                .withProperty("app.checkout.course-only.enabled", "false")
                .withProperty("app.payment.vnpay.enabled", "false")
                .withProperty("app.payment.settlement.worker.enabled", "false")
                .withProperty("app.order.expiration.scheduler.enabled", "false");
        environment.setActiveProfiles("mysql-demo-migrate");
        return environment;
    }
}
