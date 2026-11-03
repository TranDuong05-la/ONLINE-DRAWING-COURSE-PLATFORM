package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.payment.vnpay.PaymentSessionApplicationService;
import com.drawingcourse.backend.controller.PaymentSessionController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = ArtAcademyApplication.class, properties = {
        "app.payment.vnpay.enabled=true",
        "app.payment.vnpay.session-api.enabled=false",
        "app.payment.vnpay.tmn-code=DEMOV210",
        "app.payment.vnpay.secret-key=integration-test-only-secret",
        "app.payment.vnpay.return-url=https://merchant.example.test/return",
        "app.payment.vnpay.ipn-url=https://merchant.example.test/ipn"
})
@ActiveProfiles("test")
class PaymentSessionHttpGateIntegrationTest {
    @Autowired private ApplicationContext context;

    @Test
    void enablingVnPayServiceAloneDoesNotRegisterCustomerHttpRoute() {
        assertThat(context.getBeansOfType(PaymentSessionApplicationService.class)).hasSize(1);
        assertThat(context.getBeansOfType(PaymentSessionController.class)).isEmpty();
    }
}
