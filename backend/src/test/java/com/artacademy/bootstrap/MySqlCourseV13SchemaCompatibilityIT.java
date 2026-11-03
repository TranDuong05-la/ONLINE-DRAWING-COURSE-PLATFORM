package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Flyway V1-V13 and Hibernate mapping compatibility check for the explicitly acknowledged MySQL target.
 * Flyway may apply pending migrations up to V13; this class never cleans or resets the schema.
 */
@SpringBootTest(classes = ArtAcademyApplication.class)
@ActiveProfiles({"test", "mysql-course-v13-it"})
@ContextConfiguration(initializers = CourseV13MySqlTargetGuard.class)
class MySqlCourseV13SchemaCompatibilityIT {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ConfigurableApplicationContext context;

    @Test
    void flywayV1ThroughV13AndHibernateMappingsAreCompatible() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class))
                .isEqualTo(CourseV13MySqlTargetGuard.DATABASE);
        assertThat(jdbc.queryForObject("SELECT @@port", Integer.class)).isEqualTo(3306);
        String version = jdbc.queryForObject("SELECT VERSION()", String.class);
        var mysql = java.util.regex.Pattern.compile("^8\\.0\\.(\\d+)(?:[-+].*)?$").matcher(version);
        assertThat(mysql.matches()).as("MySQL 8.0.16+ is required for enforced CHECK constraints").isTrue();
        assertThat(Integer.parseInt(mysql.group(1))).isGreaterThanOrEqualTo(16);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history "
                + "WHERE success=TRUE AND version IN ('1','2','3','4','5','6','7','8','9','10','11','12','13')",
                Integer.class)).isEqualTo(13);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM flyway_schema_history WHERE success=FALSE",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT MAX(CAST(version AS UNSIGNED)) FROM flyway_schema_history "
                + "WHERE success=TRUE", Long.class)).isEqualTo(13L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM information_schema.TABLES "
                + "WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME IN "
                + "('orders','order_items','payment_events','payment_settlements','payment_settlement_work_items',"
                + "'courses','course_claims','user_course_enrollments','user_profiles')", Integer.class))
                .isEqualTo(9);
        assertThat(context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"))
                .isEqualToIgnoringCase("validate");
        assertThat(context.getEnvironment().getProperty("spring.flyway.target")).isEqualTo("13");
    }
}
