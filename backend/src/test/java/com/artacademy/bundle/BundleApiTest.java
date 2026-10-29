package com.artacademy.bundle;

import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.artacademy.bundle.dto.BundleRequest;
import com.artacademy.course.Course;
import com.artacademy.course.CourseLevel;
import com.artacademy.course.CourseStatus;
import com.artacademy.support.IntegrationTestBase;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

/** Test combo khóa học (admin + học viên). */
class BundleApiTest extends IntegrationTestBase {

    private BundleRequest request(long price, Long... courseIds) {
        return new BundleRequest(null, "Combo thử", "Mô tả", price, List.of(courseIds), null);
    }

    private long createBundle(long price, Course... courses) throws Exception {
        Long[] ids = java.util.Arrays.stream(courses).map(Course::getId).toArray(Long[]::new);
        MvcResult r = postJson("/api/admin/bundles", request(price, ids)).andExpect(status().isCreated()).andReturn();
        return readLong(r, "/id");
    }

    @Test
    void create_succeeds_andPublicSeesSavings() throws Exception {
        Course a = publishedCourse("A", 500_000, null, CourseLevel.BASIC);       // thực bán 500.000
        Course b = publishedCourse("B", 600_000, 400_000L, CourseLevel.ADVANCED); // thực bán 400.000
        long id = createBundle(800_000, a, b);

        getJson("/api/bundles/" + id)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.price").value(800_000))
                .andExpect(jsonPath("$.data.originalTotal").value(900_000))
                .andExpect(jsonPath("$.data.savings").value(100_000))
                .andExpect(jsonPath("$.data.courses.length()").value(2))
                .andExpect(jsonPath("$.data.courses[0].title").value("A"))
                .andExpect(jsonPath("$.data.courses[1].title").value("B"));
        getJson("/api/bundles").andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void create_rejectsPriceNotLowerThanTotal() throws Exception {
        Course a = publishedCourse("A", 500_000, null, CourseLevel.BASIC);
        Course b = publishedCourse("B", 400_000, null, CourseLevel.BASIC);

        postJson("/api/admin/bundles", request(900_000, a.getId(), b.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("phải nhỏ hơn tổng giá thực bán")));
        postJson("/api/admin/bundles", request(1_000_000, a.getId(), b.getId())).andExpect(status().isBadRequest());
        postJson("/api/admin/bundles", request(899_999, a.getId(), b.getId())).andExpect(status().isCreated());
    }

    @Test
    void create_usesDiscountedPriceWhenComparing() throws Exception {
        Course a = publishedCourse("A", 500_000, 100_000L, CourseLevel.BASIC);
        Course b = publishedCourse("B", 400_000, null, CourseLevel.BASIC);
        // tổng thực bán = 500.000, nên 600.000 (rẻ hơn tổng giá gốc 900.000) vẫn bị từ chối
        postJson("/api/admin/bundles", request(600_000, a.getId(), b.getId())).andExpect(status().isBadRequest());
    }

    @Test
    void create_requiresAtLeastTwoDistinctPublishedCourses() throws Exception {
        Course a = publishedCourse("A", 500_000, null, CourseLevel.BASIC);
        Course draft = newCourse("Nháp", 400_000, null, CourseLevel.BASIC, CourseStatus.DRAFT);
        Course hidden = newCourse("Ẩn", 400_000, null, CourseLevel.BASIC, CourseStatus.HIDDEN);

        postJson("/api/admin/bundles", request(100_000, a.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Combo cần ít nhất 2 khóa học"));
        postJson("/api/admin/bundles", request(100_000, a.getId(), a.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Một khóa học chỉ được chọn một lần trong combo"));
        postJson("/api/admin/bundles", request(100_000, a.getId(), draft.getId()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("chưa xuất bản")));
        postJson("/api/admin/bundles", request(100_000, a.getId(), hidden.getId())).andExpect(status().isBadRequest());
        postJson("/api/admin/bundles", request(100_000, a.getId(), 99999L))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("không tồn tại")));
        assertEquals(0, bundleRepository.count());
    }

    @Test
    void create_validatesFields() throws Exception {
        Course a = publishedCourse("A", 500_000, null, CourseLevel.BASIC);
        Course b = publishedCourse("B", 500_000, null, CourseLevel.BASIC);
        postJson("/api/admin/bundles", Map.of("title", "", "price", 0, "courseIds", List.of(a.getId(), b.getId())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[?(@.field=='title')]").exists())
                .andExpect(jsonPath("$.errors[?(@.field=='price')]").exists());
    }

    @Test
    void bundle_disappearsFromPublic_whenACourseIsHidden_andComesBack() throws Exception {
        Course a = publishedCourse("A", 500_000, null, CourseLevel.BASIC);
        Course b = publishedCourse("B", 400_000, null, CourseLevel.BASIC);
        long id = createBundle(700_000, a, b);
        getJson("/api/bundles").andExpect(jsonPath("$.data.length()").value(1));

        patchJson("/api/admin/courses/" + b.getId() + "/status", Map.of("status", "HIDDEN")).andExpect(status().isOk());

        getJson("/api/bundles").andExpect(jsonPath("$.data.length()").value(0));
        getJson("/api/bundles/" + id).andExpect(status().isNotFound());
        // admin vẫn thấy và được cảnh báo
        getJson("/api/admin/bundles/" + id)
                .andExpect(jsonPath("$.data.visibleToPublic").value(false))
                .andExpect(jsonPath("$.data.warnings[0]").value(containsString("combo bị ẩn với học viên")));

        patchJson("/api/admin/courses/" + b.getId() + "/status", Map.of("status", "PUBLISHED")).andExpect(status().isOk());
        getJson("/api/bundles").andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void toggleActive_hidesAndShowsBundle() throws Exception {
        Course a = publishedCourse("A", 500_000, null, CourseLevel.BASIC);
        Course b = publishedCourse("B", 400_000, null, CourseLevel.BASIC);
        long id = createBundle(700_000, a, b);

        patchJson("/api/admin/bundles/" + id + "/active", Map.of("active", false))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.active").value(false))
                .andExpect(jsonPath("$.data.visibleToPublic").value(false));
        getJson("/api/bundles").andExpect(jsonPath("$.data.length()").value(0));
        getJson("/api/bundles/" + id).andExpect(status().isNotFound());
        getJson("/api/admin/bundles").andExpect(jsonPath("$.data.length()").value(1));

        patchJson("/api/admin/bundles/" + id + "/active", Map.of("active", true)).andExpect(status().isOk());
        getJson("/api/bundles/" + id).andExpect(status().isOk());
        patchJson("/api/admin/bundles/" + id + "/active", Map.of()).andExpect(status().isBadRequest());
    }

    @Test
    void update_replacesCourses_andRevalidatesPrice() throws Exception {
        Course a = publishedCourse("A", 500_000, null, CourseLevel.BASIC);
        Course b = publishedCourse("B", 400_000, null, CourseLevel.BASIC);
        Course c = publishedCourse("C", 300_000, null, CourseLevel.BASIC);
        long id = createBundle(800_000, a, b);

        // thêm lại cả khóa cũ (a) lẫn khóa mới (c): không được lỗi trùng
        putJson("/api/admin/bundles/" + id, request(700_000, a.getId(), c.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.courses.length()").value(2))
                .andExpect(jsonPath("$.data.courses[1].title").value("C"))
                .andExpect(jsonPath("$.data.price").value(700_000))
                .andExpect(jsonPath("$.data.originalTotal").value(800_000));
        assertEquals(2, bundleItemRepository.count());

        // giá không hợp lệ -> từ chối và giữ nguyên dữ liệu cũ
        putJson("/api/admin/bundles/" + id, request(800_000, a.getId(), c.getId())).andExpect(status().isBadRequest());
        getJson("/api/admin/bundles/" + id).andExpect(jsonPath("$.data.price").value(700_000));
        putJson("/api/admin/bundles/99999", request(1, a.getId(), c.getId())).andExpect(status().isNotFound());
    }

    @Test
    void create_rejectsDuplicateCode() throws Exception {
        Course a = publishedCourse("A", 500_000, null, CourseLevel.BASIC);
        Course b = publishedCourse("B", 400_000, null, CourseLevel.BASIC);
        BundleRequest withCode = new BundleRequest("CB-X", "Combo", null, 700_000L, List.of(a.getId(), b.getId()), null);
        postJson("/api/admin/bundles", withCode).andExpect(status().isCreated());
        postJson("/api/admin/bundles", withCode)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Mã combo \"CB-X\" đã tồn tại"));
    }
}
