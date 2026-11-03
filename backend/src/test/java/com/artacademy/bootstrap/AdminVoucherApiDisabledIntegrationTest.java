package com.artacademy.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.artacademy.ArtAcademyApplication;
import com.drawingcourse.backend.controller.AdminVoucherController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = ArtAcademyApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:admin-voucher-disabled;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
})
@ActiveProfiles("test")
class AdminVoucherApiDisabledIntegrationTest {
    @Autowired ApplicationContext context;

    @Test
    void adminVoucherControllerIsAbsentByDefault() {
        assertThat(context.getBeansOfType(AdminVoucherController.class)).isEmpty();
    }
}
