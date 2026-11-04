package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.artacademy.payment.vnpay.PaymentSessionApplicationService;
import com.artacademy.payment.vnpay.VnPayReturnController;
import com.drawingcourse.backend.controller.PaymentSessionController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
@AutoConfigureMockMvc
class PaymentSessionHttpGateIntegrationTest {
    @Autowired private ApplicationContext context;
    @Autowired private MockMvc mvc;

    @Test
    void enablingVnPayServiceAloneDoesNotRegisterCustomerHttpRoute() {
        assertThat(context.getBeansOfType(PaymentSessionApplicationService.class)).hasSize(1);
        assertThat(context.getBeansOfType(PaymentSessionController.class)).isEmpty();
    }

    @Test
    void publicReturnPageDoesNotTreatBrowserRedirectAsPaymentEvidence() throws Exception {
        assertThat(context.getBeansOfType(VnPayReturnController.class)).hasSize(1);
        mvc.perform(get("/api/payments/vnpay/return"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(org.springframework.http.MediaType.TEXT_HTML))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("Return to DrawingCourse")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("does not confirm your payment")));
    }
}
