package com.artacademy.config;

import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;

import java.util.Map;

/** Explicitly acknowledged, target-pinned local migration profile. */
@Configuration(proxyBeanMethods = false)
@Profile("mysql-demo-migrate")
public class DrawingCourseItMigrationConfiguration {
    static final String TARGET_URL = "jdbc:mysql://127.0.0.1:3306/drawing_course_it";
    static final String DATABASE_ACK = "drawing_course_it";
    static final String MIGRATION_ACK = "apply-v14-v17-to-drawing_course_it";

    @Bean
    FlywayMigrationStrategy guardedDrawingCourseItMigrationStrategy(Environment environment) {
        return flyway -> {
            validate(environment, System.getenv());
            flyway.migrate();
        };
    }

    static void validate(Environment environment, Map<String, String> variables) {
        String[] activeProfiles = environment.getActiveProfiles();
        require(activeProfiles.length == 1 && "mysql-demo-migrate".equals(activeProfiles[0])
                        && environment.acceptsProfiles(Profiles.of("mysql-demo-migrate")),
                "Use only the mysql-demo-migrate profile");
        require(TARGET_URL.equals(environment.getProperty("spring.datasource.url")), "Unexpected datasource target");
        require(TARGET_URL.equals(environment.getProperty("spring.flyway.url")), "Unexpected Flyway target");
        require("17".equals(environment.getProperty("spring.flyway.target")), "Flyway target must be V17");
        require(environment.getProperty("spring.flyway.enabled", Boolean.class, false), "Flyway must be enabled");
        require(environment.getProperty("spring.flyway.clean-disabled", Boolean.class, false), "Flyway clean must remain disabled");
        require(environment.getProperty("spring.flyway.validate-on-migrate", Boolean.class, false), "Flyway validation must be enabled");
        require(!environment.getProperty("spring.flyway.baseline-on-migrate", Boolean.class, true), "Flyway baseline must remain disabled");
        require(!environment.getProperty("spring.flyway.out-of-order", Boolean.class, true), "Out-of-order migrations must remain disabled");
        require("none".equalsIgnoreCase(environment.getProperty("spring.main.web-application-type")),
                "Migration profile must not start the web application");
        require(DATABASE_ACK.equals(variables.get("MYSQL_IT_DATABASE_ACK")), "Set MYSQL_IT_DATABASE_ACK=drawing_course_it");
        require(MIGRATION_ACK.equals(variables.get("MYSQL_IT_MIGRATION_ACK")),
                "Set MYSQL_IT_MIGRATION_ACK=apply-v14-v17-to-drawing_course_it");
        require(nonBlank(variables.get("MYSQL_IT_USERNAME")) && nonBlank(variables.get("MYSQL_IT_PASSWORD")),
                "MYSQL_IT_USERNAME and MYSQL_IT_PASSWORD are required");
        require(variables.get("MYSQL_IT_USERNAME").equals(environment.getProperty("spring.datasource.username")),
                "Datasource username must come from MYSQL_IT_USERNAME");
        require(variables.get("MYSQL_IT_PASSWORD").equals(environment.getProperty("spring.datasource.password")),
                "Datasource password must come from MYSQL_IT_PASSWORD");
        require(variables.get("MYSQL_IT_USERNAME").equals(environment.getProperty("spring.flyway.user")),
                "Flyway username must come from MYSQL_IT_USERNAME");
        require(variables.get("MYSQL_IT_PASSWORD").equals(environment.getProperty("spring.flyway.password")),
                "Flyway password must come from MYSQL_IT_PASSWORD");
        require(!environment.getProperty("app.cart.api-enabled", Boolean.class, false)
                        && !environment.getProperty("app.cart.course-only.api-enabled", Boolean.class, false)
                        && !environment.getProperty("app.checkout.product.enabled", Boolean.class, false)
                        && !environment.getProperty("app.checkout.product.http-enabled", Boolean.class, false)
                        && !environment.getProperty("app.checkout.course-only.enabled", Boolean.class, false)
                        && !environment.getProperty("app.checkout.course-only.http-enabled", Boolean.class, false)
                        && !environment.getProperty("app.voucher.api-enabled", Boolean.class, false)
                        && !environment.getProperty("app.voucher.admin-api-enabled", Boolean.class, false)
                        && !environment.getProperty("app.payment.vnpay.enabled", Boolean.class, false)
                        && !environment.getProperty("app.payment.vnpay.session-api.enabled", Boolean.class, false)
                        && !environment.getProperty("app.payment.vnpay.ipn.enabled", Boolean.class, false)
                        && !environment.getProperty("app.payment.settlement.enabled", Boolean.class, false)
                        && !environment.getProperty("app.payment.settlement.worker.enabled", Boolean.class, false)
                        && !environment.getProperty("app.payment.settlement.worker.scheduler.enabled", Boolean.class, false)
                        && !environment.getProperty("app.order.expiration.enabled", Boolean.class, false)
                        && !environment.getProperty("app.order.expiration.scheduler.enabled", Boolean.class, false)
                        && !environment.getProperty("app.supply.dev-orders.enabled", Boolean.class, false)
                        && !environment.getProperty("app.seed.enabled", Boolean.class, false),
                "Commerce and background feature flags must remain disabled during migration");
    }

    private static boolean nonBlank(String value) { return value != null && !value.isBlank(); }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalStateException("Refusing local MySQL migration: " + message);
    }
}
