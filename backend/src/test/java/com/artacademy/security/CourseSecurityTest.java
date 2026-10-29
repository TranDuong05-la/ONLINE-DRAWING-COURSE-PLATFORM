package com.artacademy.security;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.artacademy.course.Course;
import com.artacademy.course.CourseLevel;
import com.artacademy.support.IntegrationTestBase;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

/** Phân quyền phần khóa học: xem công khai, quản trị chỉ ADMIN. */
class CourseSecurityTest extends IntegrationTestBase {

    @Test
    void publicReadEndpoints_workWithoutLogin() throws Exception {
        Course c = publishedCourse("Khóa công khai", 100_000, null, CourseLevel.BASIC);
        mvc.perform(get("/api/courses")).andExpect(status().isOk());
        mvc.perform(get("/api/courses/" + c.getId())).andExpect(status().isOk());
        mvc.perform(get("/api/bundles")).andExpect(status().isOk());
        // file upload công khai: không có file thì 404 chứ không phải 401
        mvc.perform(get("/uploads/khong-co.mp4")).andExpect(status().isNotFound());
    }

    @Test
    void adminEndpoints_rejectAnonymousUsers() throws Exception {
        mvc.perform(get("/api/admin/courses"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        mvc.perform(get("/api/admin/bundles")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/products")).andExpect(status().isUnauthorized());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/admin/courses")
                        .contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(multipart("/api/admin/uploads")
                        .file(new MockMultipartFile("file", "a.png", "image/png", new byte[]{1})))
                .andExpect(status().isUnauthorized());
        assertNoCourseCreated();
    }

    @Test
    void adminEndpoints_rejectNormalUsers_withForbidden() throws Exception {
        mvc.perform(get("/api/admin/courses").with(user("hocvien@test.local").roles("USER")))
                .andExpect(status().isForbidden());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/admin/courses")
                        .with(user("hocvien@test.local").roles("USER"))
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("title", "X", "price", 1, "level", "BASIC"))))
                .andExpect(status().isForbidden());
        assertNoCourseCreated();
    }

    @Test
    void adminEndpoints_allowAdmin() throws Exception {
        mvc.perform(get("/api/admin/courses").with(admin())).andExpect(status().isOk());
    }

    private void assertNoCourseCreated() {
        org.junit.jupiter.api.Assertions.assertEquals(0, courseRepository.count());
    }
}