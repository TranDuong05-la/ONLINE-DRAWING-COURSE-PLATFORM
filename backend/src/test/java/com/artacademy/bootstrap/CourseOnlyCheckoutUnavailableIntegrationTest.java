package com.artacademy.bootstrap;

import com.artacademy.ArtAcademyApplication;
import com.drawingcourse.backend.controller.CourseOnlyCheckoutController;
import com.drawingcourse.backend.service.checkout.CheckoutCoordinator;
import com.drawingcourse.backend.service.checkout.CourseOnlyCheckoutApplicationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Confirms explicit enablement cannot bypass missing real Member B/C ports. */
@SpringBootTest(classes = ArtAcademyApplication.class, properties = "app.checkout.course-only.enabled=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
class CourseOnlyCheckoutUnavailableIntegrationTest {
    @Autowired private ApplicationContext context;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private MockMvc mvc;

    @Test void missingCourseCatalogAndClaimAdaptersKeepBoundaryUnavailableAndCreateNoOrder() {
        assertThat(context.getBeansOfType(CheckoutCoordinator.class)).isEmpty();
        assertThat(context.getBeansOfType(CourseOnlyCheckoutApplicationService.class)).isEmpty();
        assertThat(context.getBeansOfType(CourseOnlyCheckoutController.class)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isZero();
    }

    @Test void unauthenticatedCheckoutRequestIsRejected() throws Exception {
        mvc.perform(post("/api/checkout/course-only"))
                .andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM orders", Integer.class)).isZero();
    }
}
