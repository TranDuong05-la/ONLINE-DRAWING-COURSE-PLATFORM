package com.artacademy.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

import java.io.IOException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MySqlLocalProfileConfigurationTest {

    @Test
    void pinsTheLocalDatabaseAndValidatesSchemaWithoutFlywayOrSmtp() throws IOException {
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        List<PropertySource<?>> base = loader.load("base", new ClassPathResource("application.yaml"));
        List<PropertySource<?>> local = loader.load("mysql-local", new ClassPathResource("application-mysql-local.yml"));
        MockEnvironment environment = new MockEnvironment()
                .withProperty("MYSQL_IT_USERNAME", "synthetic-it-user")
                .withProperty("MYSQL_IT_PASSWORD", "synthetic-it-password")
                .withProperty("JWT_SECRET", "synthetic-local-jwt-key-for-config-test");
        local.forEach(environment.getPropertySources()::addFirst);
        base.forEach(environment.getPropertySources()::addLast);

        assertThat(environment.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:mysql://127.0.0.1:3306/drawing_course_it");
        assertThat(environment.getProperty("spring.datasource.username")).isEqualTo("synthetic-it-user");
        assertThat(environment.getProperty("spring.datasource.password")).isEqualTo("synthetic-it-password");
        assertThat(environment.getProperty("spring.flyway.enabled", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        assertThat(environment.getProperty("server.port", Integer.class)).isEqualTo(8080);
        assertThat(environment.getProperty("spring.mail.host")).isEqualTo("localhost");
        assertThat(environment.getProperty("spring.mail.port", Integer.class)).isEqualTo(1025);
        assertThat(environment.getProperty("spring.mail.test-connection", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.mail.properties.mail.smtp.auth", Boolean.class)).isFalse();
        assertThat(environment.getProperty("spring.mail.properties.mail.smtp.starttls.enable", Boolean.class)).isFalse();
        assertThat(environment.getProperty("app.jwt.secret")).isEqualTo("synthetic-local-jwt-key-for-config-test");
        assertThat(environment.getProperty("app.public-base-url")).isEqualTo("http://10.0.2.2:8080");
        assertThat(environment.getProperty("app.payment.vnpay.return-url"))
                .isEqualTo("http://10.0.2.2:8080/api/payments/vnpay/return");
    }
}
