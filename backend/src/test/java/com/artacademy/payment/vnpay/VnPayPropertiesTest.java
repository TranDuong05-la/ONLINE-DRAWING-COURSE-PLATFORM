package com.artacademy.payment.vnpay;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.boot.env.YamlPropertySourceLoader;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;

class VnPayPropertiesTest {
    @Test
    void mysqlLocalProfilePinsDemoDatabaseAndAndroidReturnWithoutEnablingPaymentOrFlyway() throws Exception {
        var source = new YamlPropertySourceLoader().load("mysql-local",
                new ClassPathResource("application-mysql-local.yml")).getFirst();
        assertThat(source.getProperty("spring.datasource.url"))
                .isEqualTo("jdbc:mysql://127.0.0.1:3306/drawing_course_it");
        assertThat(source.getProperty("spring.flyway.enabled")).isEqualTo(false);
        assertThat(source.getProperty("app.payment.vnpay.return-url").toString())
                .contains("http://10.0.2.2:8080/api/payments/vnpay/return");
        assertThat(source.getProperty("app.payment.vnpay.allow-local-http-return-url").toString())
                .contains(":true");
        assertThat(source.getProperty("app.payment.vnpay.enabled")).isNull();
    }

    @Test
    void explicitLoopbackReturnAllowsLocalBrowserButIpnStillRequiresHttps() {
        var properties = configured();
        properties.setReturnUrl("http://localhost:8080/api/payments/vnpay/return");
        properties.setAllowLocalHttpReturnUrl(true);
        assertThatCode(properties::validateReady).doesNotThrowAnyException();
        properties.setIpnUrl("http://localhost:8080/api/payments/vnpay/ipn");
        assertThatThrownBy(properties::validateReady).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("IPN URL");
    }

    @Test
    void allowsAndroidEmulatorHostOnlyForExactReturnPath() {
        var properties = configured();
        properties.setReturnUrl("http://10.0.2.2:8080/api/payments/vnpay/return");
        properties.setAllowLocalHttpReturnUrl(true);
        assertThatCode(properties::validateReady).doesNotThrowAnyException();
        properties.setReturnUrl("http://10.0.2.2:8080/api/payments/vnpay/ipn");
        assertThatThrownBy(properties::validateReady).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void httpsReturnRemainsValidWhenLocalHttpOptInIsSet() {
        var properties = configured();
        properties.setAllowLocalHttpReturnUrl(true);
        assertThatCode(properties::validateReady).doesNotThrowAnyException();
    }

    @Test
    void localReturnOptInRejectsNonLoopbackOrUnrelatedPaths() {
        var properties = configured();
        properties.setAllowLocalHttpReturnUrl(true);
        for (String url : new String[] {
                "http://example.com:8080/api/payments/vnpay/return",
                "http://localhost:8081/api/payments/vnpay/return",
                "http://localhost:8080/other",
                "http://localhost:8080/api/payments/vnpay/return?paid=true"
        }) {
            properties.setReturnUrl(url);
            assertThatThrownBy(properties::validateReady).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    void localHttpReturnRemainsRejectedUnlessExplicitlyEnabled() {
        var properties = configured();
        properties.setReturnUrl("http://localhost:8080/api/payments/vnpay/return");
        assertThatThrownBy(properties::validateReady).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void queryDrModeMayOmitIpnButCannotRunAlongsideIpn() {
        var properties = configured();
        properties.setReturnUrl("http://10.0.2.2:8080/api/payments/vnpay/return");
        properties.setAllowLocalHttpReturnUrl(true);
        properties.setIpnUrl("");
        properties.getQueryDr().setEnabled(true);
        properties.getQueryDr().setIpAddress("127.0.0.1");
        assertThatCode(properties::validateReady).doesNotThrowAnyException();
        properties.getIpn().setEnabled(true);
        assertThatThrownBy(properties::validateReady).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mutually exclusive");
    }

    private static VnPayProperties configured() {
        var properties = new VnPayProperties();
        properties.setEnabled(true);
        properties.setTmnCode("DEMOV210");
        properties.setSecretKey("synthetic-test-secret");
        properties.setReturnUrl("https://merchant.example.test/api/payments/vnpay/return");
        properties.setIpnUrl("https://merchant.example.test/api/payments/vnpay/ipn");
        return properties;
    }
}
