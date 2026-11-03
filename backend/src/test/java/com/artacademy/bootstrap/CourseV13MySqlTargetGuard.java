package com.artacademy.bootstrap;

import java.util.Objects;

import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.Profiles;

/** Fail-closed pre-context guard for the combined V1-V13 integration schema target. */
public final class CourseV13MySqlTargetGuard
        implements ApplicationContextInitializer<ConfigurableApplicationContext> {
    static final String DATABASE = "drawing_course_it";
    static final String URL = "jdbc:mysql://127.0.0.1:3306/drawing_course_it";
    static final String MIGRATION_ACK = "apply-course-v13-to-drawing_course_it";

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        validateForTest(context.getEnvironment(), System.getenv(),
                Boolean.getBoolean("mysql.course.v13.it.enabled"));
    }

    private static boolean nonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private static boolean defaultOff(ConfigurableEnvironment env, String property) {
        return !env.getProperty(property, Boolean.class, false);
    }

    private static boolean behaviorTestOptIn(ConfigurableEnvironment env) {
        return env.getProperty("mysql.course.v13.behavior.test-opt-in", Boolean.class, false);
    }

    private static boolean allCustomerAndWorkerFlagsOff(ConfigurableEnvironment env) {
        return defaultOff(env, "app.cart.api-enabled")
                && defaultOff(env, "app.cart.course-only.api-enabled")
                && defaultOff(env, "app.checkout.course-only.enabled")
                && defaultOff(env, "app.checkout.course-only.http-enabled")
                && defaultOff(env, "app.payment.vnpay.enabled")
                && defaultOff(env, "app.payment.vnpay.session-api.enabled")
                && defaultOff(env, "app.payment.vnpay.ipn.enabled")
                && defaultOff(env, "app.payment.settlement.enabled")
                && defaultOff(env, "app.payment.settlement.worker.enabled")
                && defaultOff(env, "app.payment.settlement.worker.scheduler.enabled")
                && defaultOff(env, "app.order.expiration.enabled")
                && defaultOff(env, "app.order.expiration.scheduler.enabled");
    }

    static void validateForTest(ConfigurableEnvironment env, java.util.Map<String, String> variables,
                                boolean profileOptIn) {
        validate(env, variables::get, profileOptIn);
    }

    private static void validate(ConfigurableEnvironment env, java.util.function.Function<String, String> variable,
                                 boolean profileOptIn) {
        String configuredUrl = env.getProperty("spring.datasource.url", "");
        boolean safe = profileOptIn
                && DATABASE.equals(variable.apply("MYSQL_IT_DATABASE_ACK"))
                && MIGRATION_ACK.equals(variable.apply("MYSQL_COURSE_V13_MIGRATION_ACK"))
                && URL.equals(variable.apply("MYSQL_IT_URL"))
                && URL.equals(configuredUrl)
                && (env.getProperty("spring.flyway.url") == null
                    || URL.equals(env.getProperty("spring.flyway.url")))
                && env.acceptsProfiles(Profiles.of("mysql-course-v13-it"))
                && nonBlank(variable.apply("MYSQL_IT_USERNAME"))
                && nonBlank(variable.apply("MYSQL_IT_PASSWORD"))
                && Objects.equals(env.getProperty("spring.datasource.username"),
                    variable.apply("MYSQL_IT_USERNAME"))
                && Objects.equals(env.getProperty("spring.datasource.password"),
                    variable.apply("MYSQL_IT_PASSWORD"))
                && "com.mysql.cj.jdbc.Driver".equals(env.getProperty("spring.datasource.driver-class-name"))
                && "validate".equalsIgnoreCase(env.getProperty("spring.jpa.hibernate.ddl-auto", ""))
                && env.getProperty("spring.flyway.enabled", Boolean.class, false)
                && env.getProperty("spring.flyway.validate-on-migrate", Boolean.class, false)
                && env.getProperty("spring.flyway.clean-disabled", Boolean.class, false)
                && !env.getProperty("spring.flyway.baseline-on-migrate", Boolean.class, true)
                && "13".equals(env.getProperty("spring.flyway.target"))
                && (behaviorTestOptIn(env) || allCustomerAndWorkerFlagsOff(env));
        if (!safe) throw new IllegalStateException("Unsafe Course V13 MySQL test target");
    }
}
