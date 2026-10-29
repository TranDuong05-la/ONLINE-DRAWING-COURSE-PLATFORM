package com.artacademy.course;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.artacademy.course.dto.CourseRequest;
import com.artacademy.course.dto.LessonRequest;
import com.artacademy.course.dto.ModuleRequest;
import com.artacademy.course.dto.ReorderRequest;
import com.artacademy.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Test API quản trị: tạo/sửa khóa, xuất bản, trạng thái, module, bài học, sắp xếp. */
class CourseAdminApiTest extends IntegrationTestBase {

    private CourseRequest courseRequest(String title, long price, Long discount) {
        return new CourseRequest(null, title, "Mô tả ngắn", "Mô tả chi tiết", null, price, discount, CourseLevel.BASIC);
    }

    private LessonRequest lessonRequest(String title, String videoUrl, boolean hidden) {
        return new LessonRequest(title, null, videoUrl, 60, null, false, false, hidden);
    }

    private long createCourse() throws Exception {
        MvcResult r = postJson("/api/admin/courses", courseRequest("Vẽ màu nước", 500_000, null))
                .andExpect(status().isCreated()).andReturn();
        return readLong(r, "/course/id");
    }

    private long createModule(long courseId, String title) throws Exception {
        MvcResult r = postJson("/api/admin/courses/" + courseId + "/modules", new ModuleRequest(title, false))
                .andExpect(status().isCreated()).andReturn();
        return readLong(r, "/id");
    }

    private long createLesson(long moduleId, String title, String videoUrl) throws Exception {
        MvcResult r = postJson("/api/admin/modules/" + moduleId + "/lessons", lessonRequest(title, videoUrl, false))
                .andExpect(status().isCreated()).andReturn();
        return readLong(r, "/id");
    }

    // ------------------------------------------------------------------ tạo / sửa

    @Test
    void createCourse_startsAsDraft_withGeneratedCodeAndSlug() throws Exception {
        postJson("/api/admin/courses", courseRequest("Vẽ màu nước cơ bản", 500_000, 400_000L))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.course.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.course.slug").value("ve-mau-nuoc-co-ban"))
                .andExpect(jsonPath("$.data.course.code").exists())
                .andExpect(jsonPath("$.data.course.effectivePrice").value(400_000));
        // tên trùng thì slug được thêm số để vẫn duy nhất
        postJson("/api/admin/courses", courseRequest("Vẽ màu nước cơ bản", 500_000, null))
                .andExpect(jsonPath("$.data.course.slug").value("ve-mau-nuoc-co-ban-2"));
    }

    @Test
    void createCourse_rejectsDiscountPriceNotLessThanPrice() throws Exception {
        postJson("/api/admin/courses", courseRequest("Khóa", 500_000, 600_000L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Giá giảm phải nhỏ hơn giá gốc"));
        // bằng giá gốc cũng không hợp lệ
        postJson("/api/admin/courses", courseRequest("Khóa", 500_000, 500_000L))
                .andExpect(status().isBadRequest());
        assertEquals(0, courseRepository.count());
    }

    @Test
    void createCourse_validatesRequiredFields() throws Exception {
        Map<String, Object> bad = Map.of("title", "", "price", -5, "level", "BASIC");
        postJson("/api/admin/courses", bad)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='title')].message").value("Tên khóa học không được để trống"))
                .andExpect(jsonPath("$.errors[?(@.field=='price')].message").value("Giá gốc không được âm"));
        postJson("/api/admin/courses", Map.of("title", "A", "level", "BASIC"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='price')].message").value("Giá gốc là bắt buộc"));
    }

    @Test
    void createCourse_rejectsDuplicateCodeAndBadThumbnailUrl() throws Exception {
        postJson("/api/admin/courses", new CourseRequest("KH-ABC", "A", "m", null, null, 100L, null, CourseLevel.BASIC))
                .andExpect(status().isCreated());
        postJson("/api/admin/courses", new CourseRequest("KH-ABC", "B", "m", null, null, 100L, null, CourseLevel.BASIC))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Mã khóa học \"KH-ABC\" đã tồn tại"));
        postJson("/api/admin/courses", new CourseRequest(null, "C", "m", null, "ftp://x/y.png", 100L, null,
                CourseLevel.BASIC))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ xuất bản

    @Test
    void publish_courseWithoutLessons_isRejected() throws Exception {
        long id = createCourse();
        patchJson("/api/admin/courses/" + id + "/status", Map.of("status", "PUBLISHED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("ít nhất 1 bài học")));
        assertEquals(CourseStatus.DRAFT, courseRepository.findById(id).orElseThrow().getStatus());
    }

    @Test
    void publish_fullFlow_makesCourseVisibleToStudents() throws Exception {
        long courseId = createCourse();
        long moduleId = createModule(courseId, "Chương 1");
        createLesson(moduleId, "Bài 1", VIDEO);

        // trước khi xuất bản: học viên chưa thấy
        getJson("/api/courses/" + courseId).andExpect(status().isNotFound());

        patchJson("/api/admin/courses/" + courseId + "/status", Map.of("status", "PUBLISHED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.course.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.course.publishedAt").exists());

        getJson("/api/courses").andExpect(jsonPath("$.data.totalElements").value(1));
        getJson("/api/courses/" + courseId).andExpect(status().isOk());
    }

    @Test
    void publish_isRejected_whenAVisibleLessonHasNoVideo() throws Exception {
        long courseId = createCourse();
        long moduleId = createModule(courseId, "Chương 1");
        createLesson(moduleId, "Có video", VIDEO);
        createLesson(moduleId, "Thiếu video", null);

        patchJson("/api/admin/courses/" + courseId + "/status", Map.of("status", "PUBLISHED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("\"Thiếu video\" chưa có video")));
    }

    @Test
    void publish_ignoresHiddenLessonsWithoutVideo() throws Exception {
        long courseId = createCourse();
        long moduleId = createModule(courseId, "Chương 1");
        createLesson(moduleId, "Có video", VIDEO);
        postJson("/api/admin/modules/" + moduleId + "/lessons", lessonRequest("Đã ẩn, thiếu video", null, true))
                .andExpect(status().isCreated());

        patchJson("/api/admin/courses/" + courseId + "/status", Map.of("status", "PUBLISHED"))
                .andExpect(status().isOk());
    }

    @Test
    void publish_isRejected_withoutShortDescription() throws Exception {
        Course c = newCourse("Khóa", 100_000, null, CourseLevel.BASIC, CourseStatus.DRAFT);
        c.setShortDescription(null);
        courseRepository.save(c);
        CourseModule m = newModule(c, "M", 1, false);
        newLesson(m, "L", 1, VIDEO, false, false);

        patchJson("/api/admin/courses/" + c.getId() + "/status", Map.of("status", "PUBLISHED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("mô tả ngắn")));
    }

    @Test
    void publish_isRejected_whenAVisibleModuleHasNoVisibleLesson() throws Exception {
        Course c = newCourse("Khóa", 100_000, null, CourseLevel.BASIC, CourseStatus.DRAFT);
        CourseModule m1 = newModule(c, "Module đủ bài", 1, false);
        newLesson(m1, "L", 1, VIDEO, false, false);
        newModule(c, "Module rỗng", 2, false);

        patchJson("/api/admin/courses/" + c.getId() + "/status", Map.of("status", "PUBLISHED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("\"Module rỗng\"")));
    }

    @Test
    void adminDetail_listsPublishProblems() throws Exception {
        long id = createCourse();
        getJson("/api/admin/courses/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.publishProblems.length()").value(1))
                .andExpect(jsonPath("$.data.publishProblems[0]").value(containsString("ít nhất 1 bài học")));
    }

    // ------------------------------------------------------------------ trạng thái

    @Test
    void hidingCourse_removesItFromPublicList() throws Exception {
        Course c = publishedCourse("Khóa", 100_000, null, CourseLevel.BASIC);
        getJson("/api/courses").andExpect(jsonPath("$.data.totalElements").value(1));

        patchJson("/api/admin/courses/" + c.getId() + "/status", Map.of("status", "HIDDEN"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.course.status").value("HIDDEN"));

        getJson("/api/courses").andExpect(jsonPath("$.data.totalElements").value(0));
        getJson("/api/courses/" + c.getId()).andExpect(status().isNotFound());
        // admin vẫn thấy
        getJson("/api/admin/courses").andExpect(jsonPath("$.data.totalElements").value(1));
    }

    @Test
    void republishingHiddenCourse_checksRulesAgain() throws Exception {
        Course c = publishedCourse("Khóa", 100_000, null, CourseLevel.BASIC);
        patchJson("/api/admin/courses/" + c.getId() + "/status", Map.of("status", "HIDDEN")).andExpect(status().isOk());

        // khi đang ngừng bán, admin được tự do ẩn hết bài
        CourseLesson lesson = lessonRepository.findAllByCourse(c.getId()).get(0);
        putJson("/api/admin/lessons/" + lesson.getId(), lessonRequest("Bài 1", VIDEO, true)).andExpect(status().isOk());

        patchJson("/api/admin/courses/" + c.getId() + "/status", Map.of("status", "PUBLISHED"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("ít nhất 1 bài học")));

        // bỏ ẩn lại thì xuất bản được
        putJson("/api/admin/lessons/" + lesson.getId(), lessonRequest("Bài 1", VIDEO, false)).andExpect(status().isOk());
        patchJson("/api/admin/courses/" + c.getId() + "/status", Map.of("status", "PUBLISHED"))
                .andExpect(status().isOk());
    }

    @Test
    void statusTransitions_followTheRules() throws Exception {
        Course published = publishedCourse("Đã bán", 100_000, null, CourseLevel.BASIC);
        Course draft = newCourse("Nháp", 100_000, null, CourseLevel.BASIC, CourseStatus.DRAFT);
        String pubUrl = "/api/admin/courses/" + published.getId() + "/status";
        String draftUrl = "/api/admin/courses/" + draft.getId() + "/status";

        // đã từng xuất bản thì không quay về nháp
        patchJson(pubUrl, Map.of("status", "DRAFT"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("không thể quay về nháp")));
        // cùng trạng thái
        patchJson(pubUrl, Map.of("status", "PUBLISHED")).andExpect(status().isBadRequest());
        // nháp chưa từng bán thì không thể "ngừng bán"
        patchJson(draftUrl, Map.of("status", "HIDDEN")).andExpect(status().isBadRequest());
        // PUBLISHED <-> HIDDEN thoải mái
        patchJson(pubUrl, Map.of("status", "HIDDEN")).andExpect(status().isOk());
        patchJson(pubUrl, Map.of("status", "PUBLISHED")).andExpect(status().isOk());
        patchJson(pubUrl, Map.of("status", "KHONGCO")).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ sửa nội dung khóa đang bán

    @Test
    void publishedCourse_cannotLoseItsLastVisibleLesson() throws Exception {
        Course c = publishedCourse("Khóa", 100_000, null, CourseLevel.BASIC);
        CourseLesson lesson = lessonRepository.findAllByCourse(c.getId()).get(0);

        // ẩn bài cuối cùng
        putJson("/api/admin/lessons/" + lesson.getId(), lessonRequest("Bài 1", VIDEO, true))
                .andExpect(status().isBadRequest());
        // ẩn cả module chứa bài cuối cùng
        putJson("/api/admin/modules/" + lesson.getModule().getId(), new ModuleRequest("Module 1", true))
                .andExpect(status().isBadRequest());
        // xóa video của bài đang hiển thị
        putJson("/api/admin/lessons/" + lesson.getId(), lessonRequest("Bài 1", null, false))
                .andExpect(status().isBadRequest());
        // thêm bài mới nhưng thiếu video
        postJson("/api/admin/modules/" + lesson.getModule().getId() + "/lessons", lessonRequest("Mới", null, false))
                .andExpect(status().isBadRequest());

        // Mọi lần bị từ chối đều phải hoàn tác: dữ liệu giữ nguyên
        CourseLesson after = lessonRepository.findById(lesson.getId()).orElseThrow();
        assertFalse(after.isHidden());
        assertEquals(VIDEO, after.getVideoUrl());
        assertEquals(1, lessonRepository.findAllByCourse(c.getId()).size());
        assertFalse(moduleRepository.findById(lesson.getModule().getId()).orElseThrow().isHidden());
    }

    @Test
    void publishedCourse_canHideAnExtraLesson_andAddNewModule() throws Exception {
        Course c = publishedCourse("Khóa", 100_000, null, CourseLevel.BASIC);
        CourseModule m = moduleRepository.findByCourseIdOrderBySortOrderAscIdAsc(c.getId()).get(0);
        CourseLesson extra = newLesson(m, "Bài thứ 2", 2, VIDEO, false, false);

        putJson("/api/admin/lessons/" + extra.getId(), lessonRequest("Bài thứ 2", VIDEO, true))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hidden").value(true));
        // thêm module mới (còn rỗng) vào khóa đang bán phải được phép, để còn thêm bài vào sau
        postJson("/api/admin/courses/" + c.getId() + "/modules", new ModuleRequest("Chương mới", false))
                .andExpect(status().isCreated());
    }

    @Test
    void updatingPublishedCourse_cannotClearShortDescription() throws Exception {
        Course c = publishedCourse("Khóa", 100_000, null, CourseLevel.BASIC);
        putJson("/api/admin/courses/" + c.getId(),
                new CourseRequest(null, "Khóa", "", null, null, 100_000L, null, CourseLevel.BASIC))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("mô tả ngắn")));
        // sửa giá thì được
        putJson("/api/admin/courses/" + c.getId(),
                new CourseRequest(null, "Khóa", "Mô tả", null, null, 120_000L, 90_000L, CourseLevel.ADVANCED))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.course.price").value(120_000))
                .andExpect(jsonPath("$.data.course.discountPrice").value(90_000))
                .andExpect(jsonPath("$.data.course.level").value("ADVANCED"));
    }

    // ------------------------------------------------------------------ module / bài học / thứ tự

    @Test
    void newModulesAndLessons_areAppendedAtTheEnd_andReorderWorks() throws Exception {
        long courseId = createCourse();
        long m1 = createModule(courseId, "A");
        long m2 = createModule(courseId, "B");
        long m3 = createModule(courseId, "C");

        putJson("/api/admin/courses/" + courseId + "/modules/reorder", new ReorderRequest(List.of(m3, m1, m2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].title").value("C"))
                .andExpect(jsonPath("$.data[1].title").value("A"))
                .andExpect(jsonPath("$.data[2].title").value("B"));
        getJson("/api/admin/courses/" + courseId)
                .andExpect(jsonPath("$.data.modules[0].title").value("C"))
                .andExpect(jsonPath("$.data.modules[2].title").value("B"));

        long l1 = createLesson(m1, "L1", VIDEO);
        long l2 = createLesson(m1, "L2", VIDEO);
        long l3 = createLesson(m1, "L3", VIDEO);
        putJson("/api/admin/modules/" + m1 + "/lessons/reorder", new ReorderRequest(List.of(l2, l3, l1)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].title").value("L2"))
                .andExpect(jsonPath("$.data[2].title").value("L1"));
    }

    @Test
    void reorder_requiresExactlyAllIds() throws Exception {
        long courseId = createCourse();
        long m1 = createModule(courseId, "A");
        long m2 = createModule(courseId, "B");

        putJson("/api/admin/courses/" + courseId + "/modules/reorder", new ReorderRequest(List.of(m1)))
                .andExpect(status().isBadRequest());
        putJson("/api/admin/courses/" + courseId + "/modules/reorder", new ReorderRequest(List.of(m1, m1)))
                .andExpect(status().isBadRequest());
        putJson("/api/admin/courses/" + courseId + "/modules/reorder", new ReorderRequest(List.of(m1, m2, 99999L)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void lessons_storeFlagsAndDocument_andValidateInput() throws Exception {
        long courseId = createCourse();
        long moduleId = createModule(courseId, "A");

        postJson("/api/admin/modules/" + moduleId + "/lessons",
                new LessonRequest("Bài học thử", "Mô tả", "/uploads/a.mp4", 125, "/uploads/b.pdf", true, true, false))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.preview").value(true))
                .andExpect(jsonPath("$.data.required").value(true))
                .andExpect(jsonPath("$.data.durationSeconds").value(125))
                .andExpect(jsonPath("$.data.materialUrl").value("/uploads/b.pdf"));

        postJson("/api/admin/modules/" + moduleId + "/lessons", lessonRequest("", VIDEO, false))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='title')]").exists());
        postJson("/api/admin/modules/" + moduleId + "/lessons",
                new LessonRequest("X", null, VIDEO, -5, null, null, null, null))
                .andExpect(status().isBadRequest());
        postJson("/api/admin/modules/" + moduleId + "/lessons", lessonRequest("X", "javascript:alert(1)", false))
                .andExpect(status().isBadRequest());
        postJson("/api/admin/modules/99999/lessons", lessonRequest("X", VIDEO, false))
                .andExpect(status().isNotFound());
        putJson("/api/admin/lessons/99999", lessonRequest("X", VIDEO, false)).andExpect(status().isNotFound());
    }

    @Test
    void deleteEndpointsDoNotExist_becauseWeOnlyHide() throws Exception {
        Course c = publishedCourse("Khóa", 100_000, null, CourseLevel.BASIC);
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/admin/courses/" + c.getId()).with(admin()))
                .andExpect(status().isMethodNotAllowed());
        assertEquals(1, courseRepository.count());
    }

    @Test
    void adminList_showsAllStatuses_andCanFilter() throws Exception {
        publishedCourse("Bán", 100_000, null, CourseLevel.BASIC);
        newCourse("Nháp", 100_000, null, CourseLevel.BASIC, CourseStatus.DRAFT);
        newCourse("Ẩn", 100_000, null, CourseLevel.BASIC, CourseStatus.HIDDEN);

        getJson("/api/admin/courses").andExpect(jsonPath("$.data.totalElements").value(3));
        getJson("/api/admin/courses?status=DRAFT")
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].title").value("Nháp"));
        getJson("/api/admin/courses?keyword=ẩn").andExpect(jsonPath("$.data.totalElements").value(1));
    }
}
