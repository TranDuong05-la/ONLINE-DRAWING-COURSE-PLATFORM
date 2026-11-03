package com.artacademy.bootstrap;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

import java.util.Objects;

/** Fail-closed guard dedicated to the separately authorized Refund V10 migration/test path. */
public final class RefundV10MySqlTargetGuard
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    static final String EXPECTED_DATABASE = "drawing_course_it";
    static final String EXPECTED_URL = "jdbc:mysql://127.0.0.1:3306/drawing_course_it";
    static final String EXPECTED_ACK = "apply-refund-v10-to-drawing_course_it";
    static final String EXPECTED_CONFIG_IMPORT =
            "optional:classpath:/application-mysql-refund-v10-it.yml";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        ConfigurableEnvironment env = context.getEnvironment();
        String configured = env.getProperty("spring.datasource.url", "");
        boolean safe = Boolean.getBoolean("refund.mysql.v10.it.enabled")
                && EXPECTED_DATABASE.equals(System.getenv("MYSQL_IT_DATABASE_ACK"))
                && EXPECTED_ACK.equals(System.getenv("MYSQL_REFUND_V10_MIGRATION_ACK"))
                && EXPECTED_CONFIG_IMPORT.equals(System.getenv("SPRING_CONFIG_IMPORT"))
                && EXPECTED_URL.equals(System.getenv("MYSQL_IT_URL"))
                && EXPECTED_URL.equals(configured)
                && (env.getProperty("spring.flyway.url") == null
                    || EXPECTED_URL.equals(env.getProperty("spring.flyway.url")))
                && env.acceptsProfiles(Profiles.of("mysql-refund-v10-it"))
                && nonBlank(System.getenv("MYSQL_IT_USERNAME"))
                && System.getenv("MYSQL_IT_PASSWORD") != null
                && Objects.equals(env.getProperty("spring.datasource.username"), System.getenv("MYSQL_IT_USERNAME"))
                && Objects.equals(env.getProperty("spring.datasource.password"), System.getenv("MYSQL_IT_PASSWORD"))
                && "com.mysql.cj.jdbc.Driver".equals(env.getProperty("spring.datasource.driver-class-name"))
                && "validate".equalsIgnoreCase(env.getProperty("spring.jpa.hibernate.ddl-auto", ""))
                && env.getProperty("spring.flyway.enabled", Boolean.class, false)
                && env.getProperty("spring.flyway.validate-on-migrate", Boolean.class, false)
                && env.getProperty("spring.flyway.clean-disabled", Boolean.class, false)
                && !env.getProperty("spring.flyway.baseline-on-migrate", Boolean.class, true)
                && "10".equals(env.getProperty("spring.flyway.target"));
        if (!safe) {
            throw new IllegalStateException("Refund V10 MySQL verification requires the separately acknowledged "
                    + "drawing_course_it migration target; no datasource or Flyway bean was initialized.");
        }
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }
}
