package com.artacademy.course.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.artacademy.bundle.BundleItem;
import com.artacademy.bundle.BundleSellableService;
import com.artacademy.bundle.CourseBundle;
import com.artacademy.common.NotFoundException;
import com.artacademy.course.Course;
import com.artacademy.course.CourseLesson;
import com.artacademy.course.CourseLevel;
import com.artacademy.course.CourseModule;
import com.artacademy.course.CourseStatus;
import com.artacademy.product.Product;
import com.artacademy.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Test các service Java dành cho thành viên D (giỏ hàng), C (học tập), E (kho). */
class IntegrationServicesTest extends IntegrationTestBase {

    @Autowired CourseSellableService sellableService;
    @Autowired BundleSellableService bundleSellableService;
    @Autowired CourseContentService contentService;

    @Test
    void getSellableCourse_returnsPricesAndActiveKits() throws Exception {
        Course c = publishedCourse("Khóa", 600_000, 449_000L, CourseLevel.ADVANCED);
        Product active = newProduct("SKU-A", true);
        Product inactive = newProduct("SKU-OFF", false);
        newKit(c, active, 2);
        newKit(c, inactive, 1);

        SellableCourse s = sellableService.getSellableCourse(c.getId());

        assertEquals("Khóa", s.title());
        assertEquals(600_000, s.price());
        assertEquals(449_000L, s.discountPrice());
        assertEquals(449_000, s.effectivePrice());
        assertEquals(1, s.kits().size());
        assertEquals("SKU-A", s.kits().get(0).sku());
        assertEquals(2, s.kits().get(0).quantity());
    }

    @Test
    void getSellableCourse_throwsWhenNotPublished() {
        Course draft = newCourse("Nháp", 100_000, null, CourseLevel.BASIC, CourseStatus.DRAFT);
        Course hidden = newCourse("Ẩn", 100_000, null, CourseLevel.BASIC, CourseStatus.HIDDEN);

        assertThrows(NotFoundException.class, () -> sellableService.getSellableCourse(draft.getId()));
        assertThrows(NotFoundException.class, () -> sellableService.getSellableCourse(hidden.getId()));
        assertThrows(NotFoundException.class, () -> sellableService.getSellableCourse(99999L));
    }

    @Test
    void getSellableBundle_returnsCoursesAndThrowsWhenNotSellable() {
        Course a = publishedCourse("A", 500_000, null, CourseLevel.BASIC);
        Course b = publishedCourse("B", 400_000, null, CourseLevel.BASIC);
        CourseBundle bundle = new CourseBundle();
        bundle.setCode("CB-1");
        bundle.setTitle("Combo");
        bundle.setPrice(700_000);
        bundleRepository.save(bundle);
        bundleItemRepository.save(new BundleItem(bundle, a));
        bundleItemRepository.save(new BundleItem(bundle, b));

        BundleSellableService.SellableBundle s = bundleSellableService.getSellableBundle(bundle.getId());
        assertEquals(700_000, s.price());
        assertEquals(900_000, s.originalTotal());
        assertEquals(2, s.courses().size());

        bundle.setActive(false);
        bundleRepository.save(bundle);
        assertThrows(NotFoundException.class, () -> bundleSellableService.getSellableBundle(bundle.getId()));
    }

    @Test
    void courseStructure_hasVisibleItemsInOrderWithRequiredFlag() {
        Course c = newCourse("Khóa", 100_000, null, CourseLevel.BASIC, CourseStatus.PUBLISHED);
        CourseModule m2 = newModule(c, "M2", 2, false);
        CourseModule m1 = newModule(c, "M1", 1, false);
        CourseModule hiddenModule = newModule(c, "M ẩn", 3, true);
        CourseLesson required = newLesson(m1, "Bắt buộc", 1, VIDEO, false, false);
        required.setRequired(true);
        lessonRepository.save(required);
        newLesson(m1, "Tùy chọn", 2, VIDEO, false, false);
        newLesson(m2, "Bài ở M2", 1, VIDEO, false, false);
        newLesson(m2, "Bài ẩn", 2, VIDEO, false, true);
        newLesson(hiddenModule, "Trong module ẩn", 1, VIDEO, false, false);

        CourseStructure s = contentService.getCourseStructure(c.getId());

        assertEquals(2, s.modules().size());
        assertEquals("M1", s.modules().get(0).title());
        assertEquals("Bắt buộc", s.modules().get(0).lessons().get(0).title());
        assertTrue(s.modules().get(0).lessons().get(0).required());
        assertEquals(1, s.modules().get(1).lessons().size());
    }

    @Test
    void lessonContent_hasVideoAndDocument_andHiddenCourseStillReadableButDraftIsNot() {
        Course hidden = newCourse("Ngừng bán", 100_000, null, CourseLevel.BASIC, CourseStatus.HIDDEN);
        CourseLesson lesson = newLesson(newModule(hidden, "M", 1, false), "Bài", 1, VIDEO, false, false);
        lesson.setMaterialUrl("/uploads/doc.pdf");
        lessonRepository.save(lesson);

        LessonContent content = contentService.getLessonContent(lesson.getId());
        assertEquals(VIDEO, content.videoUrl());
        assertEquals("/uploads/doc.pdf", content.documentUrl());
        assertEquals(hidden.getId(), content.courseId());
        // người đã mua khóa bị ngừng bán vẫn xem được cấu trúc
        assertEquals(1, contentService.getCourseStructure(hidden.getId()).modules().size());

        Course draft = newCourse("Nháp", 100_000, null, CourseLevel.BASIC, CourseStatus.DRAFT);
        CourseLesson draftLesson = newLesson(newModule(draft, "M", 1, false), "Bài", 1, VIDEO, false, false);
        assertThrows(NotFoundException.class, () -> contentService.getLessonContent(draftLesson.getId()));
        assertThrows(NotFoundException.class, () -> contentService.getCourseStructure(draft.getId()));
        assertThrows(NotFoundException.class, () -> contentService.getLessonContent(99999L));
    }

    @Test
    void hiddenLesson_isOutOfStructure_butItsContentStaysReadable() {
        Course c = newCourse("Khóa", 100_000, null, CourseLevel.BASIC, CourseStatus.PUBLISHED);
        CourseModule m = newModule(c, "M", 1, false);
        newLesson(m, "Hiện", 1, VIDEO, false, false);
        CourseLesson hidden = newLesson(m, "Đã ẩn", 2, VIDEO, false, true);

        assertEquals(1, contentService.getCourseStructure(c.getId()).modules().get(0).lessons().size());
        // dữ liệu tiến độ cũ của người đã học vẫn tra cứu được
        assertEquals("Đã ẩn", contentService.getLessonContent(hidden.getId()).title());
    }
}
