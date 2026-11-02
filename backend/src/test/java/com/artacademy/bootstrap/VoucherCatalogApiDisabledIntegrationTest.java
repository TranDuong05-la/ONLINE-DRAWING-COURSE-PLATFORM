package com.artacademy.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import com.artacademy.ArtAcademyApplication;
import com.drawingcourse.backend.controller.VoucherCatalogController;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;

@SpringBootTest(classes = ArtAcademyApplication.class, properties = {
        "app.voucher.api-enabled=false",
        "spring.datasource.url=jdbc:h2:mem:voucher-catalog-disabled;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false"
})
@ActiveProfiles("test")
class VoucherCatalogApiDisabledIntegrationTest {
    @Autowired ApplicationContext context;

    @Test void endpointIsUnavailableWhenFeatureIsDisabled() {
        assertThat(context.getBeansOfType(VoucherCatalogController.class)).isEmpty();
    }
}
