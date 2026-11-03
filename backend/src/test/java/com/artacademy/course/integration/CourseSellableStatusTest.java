package com.artacademy.course.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.artacademy.common.NotFoundException;
import com.artacademy.course.Course;
import com.artacademy.course.CourseIncludedKit;
import com.artacademy.course.CourseLevel;
import com.artacademy.course.CourseStatus;
import com.artacademy.product.ProductCatalog.KitProduct;
import com.artacademy.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Quà tặng kèm là bắt buộc: khóa lẻ thiếu quà vẫn hiển thị (có cờ "Tạm thiếu quà") nhưng không bán được,
 * và B không tự bỏ quà để tiếp tục bán.
 */
class CourseSellableStatusTest extends IntegrationTestBase {

    @Autowired CourseSellableService service;

    @Test
    void courseWithoutKits_isSellable() {
        Course c = publishedCourse("Khóa", 100_000, null, CourseLevel.BASIC);

        SellableCourse s = service.requireSellable(c.getId());

        assertEquals(CourseSellableStatus.AVAILABLE, s.status());
        assertTrue(s.isSellable());
    }

    @Test
    void courseWithActiveKits_isSellable() {
        Course c = publishedCourse("Khóa", 100_000, null, CourseLevel.BASIC);
        newKit(c, newProduct("A", true), 1);

        assertTrue(service.requireSellable(c.getId()).isSellable());
    }

    @Test
    void courseWithAnInactiveKit_isNotSellable_andTheKitIsReportedNotDropped() {
        Course c = publishedCourse("Khóa", 100_000, null, CourseLevel.BASIC);
        KitProduct ok = newProduct("OK", true);
        KitProduct off = newProduct("OFF", false);
        newKit(c, ok, 1);
        newKit(c, off, 2);

        SellableCourse s = service.getSellableCourse(c.getId());

        assertEquals(CourseSellableStatus.KIT_UNAVAILABLE, s.status());
        assertFalse(s.isSellable());
        assertEquals(1, s.kits().size());
        assertEquals(1, s.unavailableKits().size());
        assertEquals(SellableCourse.UnavailableKit.INACTIVE, s.unavailableKits().get(0).reason());
        assertEquals(2, kitRepository.count(), "B không tự bỏ quà khỏi khóa");

        CourseNotSellableException e = assertThrows(CourseNotSellableException.class,
                () -> service.requireSellable(c.getId()));
        assertEquals(CourseSellableStatus.KIT_UNAVAILABLE, e.getSellableStatus());
        assertEquals("COURSE_NOT_SELLABLE", e.getErrorCode());
        assertEquals(409, e.getStatus().value());
    }

    @Test
    void courseWithAKitWhoseProductIsGone_isNotSellable() {
        Course c = publishedCourse("Khóa", 100_000, null, CourseLevel.BASIC);
        kitRepository.save(new CourseIncludedKit(c, 424242, 1));

        SellableCourse s = service.getSellableCourse(c.getId());

        assertFalse(s.isSellable());
        assertEquals(SellableCourse.UnavailableKit.NOT_FOUND, s.unavailableKits().get(0).reason());
    }

    @Test
    void courseBecomesSellableAgainWhenTheProductIsBackOnSale() {
        Course c = publishedCourse("Khóa", 100_000, null, CourseLevel.BASIC);
        KitProduct gift = newProduct("GIFT", false);
        newKit(c, gift, 1);
        assertFalse(service.getSellableCourse(c.getId()).isSellable());

        productCatalog.setActive(gift.id(), true);

        assertTrue(service.requireSellable(c.getId()).isSellable());
    }

    @Test
    void unpublishedOrMissingCourse_isStillNotFound_notNotSellable() {
        Course draft = newCourse("Nháp", 100_000, null, CourseLevel.BASIC, CourseStatus.DRAFT);

        assertThrows(NotFoundException.class, () -> service.requireSellable(draft.getId()));
        assertThrows(NotFoundException.class, () -> service.requireSellable(99999));
    }

    @Test
    void publicApi_showsTheCourseWithAFlagInsteadOfHidingIt() throws Exception {
        Course c = publishedCourse("Khóa có quà", 100_000, null, CourseLevel.BASIC);
        newKit(c, newProduct("OFF", false), 1);
        Course plain = publishedCourse("Khóa thường", 100_000, null, CourseLevel.BASIC);

        getJson("/api/courses")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.content[?(@.id==" + c.getId() + ")].kitsUnavailable").value(true))
                .andExpect(jsonPath("$.data.content[?(@.id==" + c.getId() + ")].hasKits").value(false))
                .andExpect(jsonPath("$.data.content[?(@.id==" + plain.getId() + ")].kitsUnavailable").value(false));
        getJson("/api/courses/" + c.getId())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.course.kitsUnavailable").value(true))
                .andExpect(jsonPath("$.data.kits.length()").value(0));
    }
}
