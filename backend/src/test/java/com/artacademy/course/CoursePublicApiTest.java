package com.artacademy.course;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.artacademy.support.IntegrationTestBase;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

/** Test API dành cho học viên: GET /api/courses và GET /api/courses/{id}. */
class CoursePublicApiTest extends IntegrationTestBase {

    @Test
    void list_onlyReturnsPublishedCourses() throws Exception {
        Course published = publishedCourse("Khóa đang bán", 100_000, null, CourseLevel.BASIC);
        newCourse("Khóa nháp", 100_000, null, CourseLevel.BASIC, CourseStatus.DRAFT);
        newCourse("Khóa ngừng bán", 100_000, null, CourseLevel.BASIC, CourseStatus.HIDDEN);

        getJson("/api/courses")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].id").value(published.getId()))
                .andExpect(jsonPath("$.data.content[0].status").value("PUBLISHED"));
    }

    @Test
    void detail_ofDraftOrHiddenCourse_returns404() throws Exception {
        Course draft = newCourse("Nháp", 100_000, null, CourseLevel.BASIC, CourseStatus.DRAFT);
        Course hidden = newCourse("Ẩn", 100_000, null, CourseLevel.BASIC, CourseStatus.HIDDEN);

        getJson("/api/courses/" + draft.getId())
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value("Không tìm thấy khóa học"));
        getJson("/api/courses/" + hidden.getId()).andExpect(status().isNotFound());
        getJson("/api/courses/999999").andExpect(status().isNotFound());
    }

    @Test
    void detail_returnsLessonsInOrder_andNeverLeaksVideoOfNonPreviewLessons() throws Exception {
        Course course = newCourse("Khóa A", 100_000, null, CourseLevel.BASIC, CourseStatus.PUBLISHED);
        // Cố tình tạo thứ tự lộn xộn để chắc chắn kết quả được sắp theo sort_order
        CourseModule m2 = newModule(course, "Module 2", 2, false);
        CourseModule m1 = newModule(course, "Module 1", 1, false);
        CourseModule hiddenModule = newModule(course, "Module ẩn", 3, true);
        CourseLesson b = newLesson(m1, "Bài 1-b", 2, "https://x.test/secret-b.mp4", false, false);
        b.setMaterialUrl("https://x.test/secret-doc.pdf");
        lessonRepository.save(b);
        newLesson(m1, "Bài 1-a", 1, "https://x.test/preview-a.mp4", true, false);
        newLesson(m2, "Bài 2", 1, "https://x.test/secret-2.mp4", false, false);
        newLesson(m2, "Bài đã ẩn", 2, "https://x.test/secret-hidden.mp4", false, true);
        newLesson(hiddenModule, "Bài trong module ẩn", 1, "https://x.test/secret-3.mp4", false, false);

        getJson("/api/courses/" + course.getId())
                .andExpect(status().isOk())
                // module ẩn không xuất hiện, còn lại đúng thứ tự
                .andExpect(jsonPath("$.data.modules.length()").value(2))
                .andExpect(jsonPath("$.data.modules[0].title").value("Module 1"))
                .andExpect(jsonPath("$.data.modules[1].title").value("Module 2"))
                // bài đúng thứ tự, bài ẩn không xuất hiện
                .andExpect(jsonPath("$.data.modules[0].lessons[0].title").value("Bài 1-a"))
                .andExpect(jsonPath("$.data.modules[0].lessons[1].title").value("Bài 1-b"))
                .andExpect(jsonPath("$.data.modules[1].lessons.length()").value(1))
                // bài học thử có previewVideoUrl, bài thường thì không
                .andExpect(jsonPath("$.data.modules[0].lessons[0].preview").value(true))
                .andExpect(jsonPath("$.data.modules[0].lessons[0].previewVideoUrl").value("https://x.test/preview-a.mp4"))
                .andExpect(jsonPath("$.data.modules[0].lessons[1].previewVideoUrl").value(nullValue()))
                // tài liệu: chỉ biết "có tài liệu", không có link
                .andExpect(jsonPath("$.data.modules[0].lessons[1].hasDocument").value(true))
                .andExpect(jsonPath("$.data.modules[0].lessons[0].hasDocument").value(false))
                // toàn bộ JSON không được chứa link nào của bài trả phí
                .andExpect(content().string(not(containsString("secret"))))
                .andExpect(content().string(not(containsString(".pdf"))))
                // thống kê chỉ tính bài hiển thị
                .andExpect(jsonPath("$.data.course.lessonCount").value(3))
                .andExpect(jsonPath("$.data.course.totalDurationSeconds").value(180));
    }

    @Test
    void list_searchByTitle_isCaseInsensitive() throws Exception {
        publishedCourse("Màu nước cơ bản", 500_000, null, CourseLevel.BASIC);
        publishedCourse("Chân dung nâng cao", 600_000, null, CourseLevel.ADVANCED);

        getJson("/api/courses?keyword=MÀU NƯỚC")
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].title").value("Màu nước cơ bản"));
        getJson("/api/courses?keyword=chân")
                .andExpect(jsonPath("$.data.content[0].title").value("Chân dung nâng cao"));
        getJson("/api/courses?keyword=khongco").andExpect(jsonPath("$.data.totalElements").value(0));
        // ký tự đặc biệt của LIKE phải được coi là chữ thường, không phải "khớp tất cả"
        mvc.perform(get("/api/courses").param("keyword", "%")).andExpect(jsonPath("$.data.totalElements").value(0));
        mvc.perform(get("/api/courses").param("keyword", "_")).andExpect(jsonPath("$.data.totalElements").value(0));
    }

    @Test
    void list_filterByLevel() throws Exception {
        publishedCourse("Cơ bản 1", 100_000, null, CourseLevel.BASIC);
        publishedCourse("Cơ bản 2", 100_000, null, CourseLevel.BASIC);
        publishedCourse("Nâng cao 1", 100_000, null, CourseLevel.ADVANCED);

        getJson("/api/courses?level=BASIC").andExpect(jsonPath("$.data.totalElements").value(2));
        getJson("/api/courses?level=advanced")
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].level").value("ADVANCED"));
        getJson("/api/courses?level=KHONGCO")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("level chỉ nhận: BASIC, ADVANCED"));
    }

    @Test
    void list_sortByEffectivePrice_usesDiscountPriceWhenPresent() throws Exception {
        // Giá thực bán: A = 500.000, B = 300.000 (giảm từ 600.000), C = 400.000
        publishedCourse("A", 500_000, null, CourseLevel.BASIC);
        publishedCourse("B", 600_000, 300_000L, CourseLevel.ADVANCED);
        publishedCourse("C", 400_000, null, CourseLevel.BASIC);

        getJson("/api/courses?sort=price_asc")
                .andExpect(jsonPath("$.data.content[0].title").value("B"))
                .andExpect(jsonPath("$.data.content[1].title").value("C"))
                .andExpect(jsonPath("$.data.content[2].title").value("A"))
                .andExpect(jsonPath("$.data.content[0].effectivePrice").value(300_000));
        getJson("/api/courses?sort=price_desc")
                .andExpect(jsonPath("$.data.content[0].title").value("A"))
                .andExpect(jsonPath("$.data.content[1].title").value("C"))
                .andExpect(jsonPath("$.data.content[2].title").value("B"));
    }

    @Test
    void list_sortByNewest_putsLatestPublishedFirst() throws Exception {
        LocalDateTime base = LocalDateTime.now().minusDays(10);
        for (int i = 1; i <= 3; i++) {
            Course c = publishedCourse("Khóa " + i, 100_000, null, CourseLevel.BASIC);
            c.setPublishedAt(base.plusDays(i));
            courseRepository.save(c);
        }

        getJson("/api/courses?sort=newest")
                .andExpect(jsonPath("$.data.content[0].title").value("Khóa 3"))
                .andExpect(jsonPath("$.data.content[2].title").value("Khóa 1"));
        // mặc định là newest
        getJson("/api/courses").andExpect(jsonPath("$.data.content[0].title").value("Khóa 3"));
    }

    @Test
    void list_isPaginated() throws Exception {
        for (int i = 1; i <= 3; i++) {
            publishedCourse("Khóa " + i, 100_000 * i, null, CourseLevel.BASIC);
        }

        getJson("/api/courses?size=2&page=0&sort=price_asc")
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(3))
                .andExpect(jsonPath("$.data.totalPages").value(2))
                .andExpect(jsonPath("$.data.page").value(0));
        getJson("/api/courses?size=2&page=1&sort=price_asc")
                .andExpect(jsonPath("$.data.content.length()").value(1))
                .andExpect(jsonPath("$.data.content[0].title").value("Khóa 3"));
    }

    @Test
    void list_rejectsInvalidParameters() throws Exception {
        getJson("/api/courses?sort=abc").andExpect(status().isBadRequest());
        getJson("/api/courses?size=0").andExpect(status().isBadRequest());
        getJson("/api/courses?size=1000").andExpect(status().isBadRequest());
        getJson("/api/courses?page=-1").andExpect(status().isBadRequest());
        getJson("/api/courses?page=abc")
                .andExpect(status().isBadRequest())
                // Thông báo lỗi kiểu tham số do GlobalExceptionHandler của A tạo ra (tiếng Anh)
                .andExpect(jsonPath("$.message").value("Invalid value 'abc' for parameter 'page'"));
    }

    @Test
    void list_showsDiscountAndGiftBadgeInfo() throws Exception {
        Course withGift = publishedCourse("Có quà", 450_000, null, CourseLevel.BASIC);
        publishedCourse("Giảm giá", 600_000, 449_000L, CourseLevel.ADVANCED);
        newKit(withGift, newProduct("SKU-1", true), 1);

        getJson("/api/courses?sort=price_asc")
                .andExpect(jsonPath("$.data.content[0].title").value("Giảm giá"))
                .andExpect(jsonPath("$.data.content[0].price").value(600_000))
                .andExpect(jsonPath("$.data.content[0].discountPrice").value(449_000))
                .andExpect(jsonPath("$.data.content[0].hasKits").value(false))
                .andExpect(jsonPath("$.data.content[1].title").value("Có quà"))
                .andExpect(jsonPath("$.data.content[1].discountPrice").value(nullValue()))
                .andExpect(jsonPath("$.data.content[1].hasKits").value(true));
    }

    @Test
    void errorResponse_usesApiResponseEnvelope() throws Exception {
        getJson("/api/courses/999999")
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.timestamp").exists())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.timestamp").exists());
    }
}
