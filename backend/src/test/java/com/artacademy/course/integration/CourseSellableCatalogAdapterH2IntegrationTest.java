package com.artacademy.course.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.artacademy.course.Course;
import com.artacademy.course.CourseLevel;
import com.artacademy.course.CourseStatus;
import com.artacademy.product.Product;
import com.artacademy.support.IntegrationTestBase;
import com.drawingcourse.backend.service.checkout.CourseCatalogPort;
import com.drawingcourse.backend.service.checkout.CourseSellableCatalogAdapter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.TestPropertySource;

@TestPropertySource(properties = "app.checkout.course-only.catalog-adapter.enabled=true")
class CourseSellableCatalogAdapterH2IntegrationTest extends IntegrationTestBase {
    @Autowired private CourseSellableCatalogAdapter adapter;

    @Test
    void registeredAdapterMapsRealPublishedCourseAndTrustedDiscountPrice() {
        Course course = publishedCourse("Real catalog course", 120_000, 89_000L, CourseLevel.BASIC);

        CourseCatalogPort.CourseOffer offer = adapter.findForCheckout(course.getId().intValue()).orElseThrow();

        assertEquals(course.getId().intValue(), offer.courseId());
        assertEquals(course.getTitle(), offer.name());
        assertEquals(89_000, offer.unitPriceVnd());
        assertTrue(offer.purchasable());
    }

    @Test
    void realCatalogRejectsDraftAndMissingCourse() {
        Course draft = newCourse("Draft", 100_000, null, CourseLevel.BASIC, CourseStatus.DRAFT);

        assertTrue(adapter.findForCheckout(draft.getId().intValue()).isEmpty());
        assertTrue(adapter.findForCheckout(Integer.MAX_VALUE).isEmpty());
    }

    @Test
    void realCatalogRejectsCoursesWithActiveOrInactivePhysicalKits() {
        Course activeKitCourse = publishedCourse("Active kit", 100_000, null, CourseLevel.BASIC);
        Product active = newProduct("SKU-ACTIVE", true);
        newKit(activeKitCourse, active, 1);

        Course inactiveKitCourse = publishedCourse("Inactive kit", 100_000, null, CourseLevel.BASIC);
        Product inactive = newProduct("SKU-INACTIVE", false);
        newKit(inactiveKitCourse, inactive, 1);

        assertTrue(adapter.findForCheckout(activeKitCourse.getId().intValue()).isEmpty());
        // CourseSellableService omits inactive Product kits from its projection; the adapter still rejects the source association.
        assertTrue(adapter.findForCheckout(inactiveKitCourse.getId().intValue()).isEmpty());
    }
}