package com.artacademy.common;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.artacademy.course.CourseRepository;
import com.artacademy.course.CourseStatus;
import com.artacademy.product.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

/** Kiểm tra dữ liệu mẫu tự thêm khi khởi động (DB riêng, bật app.seed.enabled). */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:artnest_seed;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.seed.enabled=true",
        "app.upload.dir=target/test-uploads",
        "app.seed.users.enabled=true",
        "app.seed.admin.email=seed-admin@test.local",
        "app.seed.admin.password=Seed@Admin2026",
        "app.seed.learner.email=seed-learner@test.local",
        "app.seed.learner.password=Seed@Learner2026"
})
@AutoConfigureMockMvc
@org.springframework.test.context.ActiveProfiles("test")
class DataSeederTest {

    @Autowired MockMvc mvc;
    @Autowired CourseRepository courseRepository;
    @Autowired ProductRepository productRepository;

    @Test
    void seedData_hasExpectedCoursesProductsAndBundle() throws Exception {
        assertTrue(productRepository.count() >= 3);
        assertEquals(3, courseRepository.count());
        assertEquals(1, courseRepository.findAll().stream().filter(c -> c.getStatus() == CourseStatus.DRAFT).count());

        // học viên chỉ thấy 2 khóa đã xuất bản: 1 khóa có quà, 1 khóa có giá giảm
        mvc.perform(get("/api/courses?sort=price_asc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalElements").value(2))
                .andExpect(jsonPath("$.data.content[0].discountPrice").value(449_000))
                .andExpect(jsonPath("$.data.content[1].hasKits").value(true));

        // mỗi khóa đã xuất bản có bài học thử, và bài thường không lộ video
        mvc.perform(get("/api/courses/" + courseRepository.findAll().get(0).getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.modules[0].lessons[0].preview").value(true))
                .andExpect(jsonPath("$.data.modules[0].lessons[0].previewVideoUrl").exists());

        mvc.perform(get("/api/bundles")).andExpect(jsonPath("$.data.length()").value(1));
    }

    /** Tài khoản mẫu đăng nhập được bằng JWT thật của A, và quyền ADMIN được áp dụng cho /api/admin/**. */
    @Test
    void seededAccounts_canLogin_andAdminRoleIsEnforced() throws Exception {
        String adminToken = login("seed-admin@test.local", "Seed@Admin2026");
        String learnerToken = login("seed-learner@test.local", "Seed@Learner2026");

        mvc.perform(get("/api/admin/courses").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalElements").value(3));
        // học viên (USER) bị cấm, người chưa đăng nhập bị 401
        mvc.perform(get("/api/admin/courses").header("Authorization", "Bearer " + learnerToken))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/courses")).andExpect(status().isUnauthorized());
        // API xem khóa học thì ai cũng gọi được
        mvc.perform(get("/api/courses")).andExpect(status().isOk());
        mvc.perform(get("/api/bundles")).andExpect(status().isOk());
    }

    private String login(String email, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return new ObjectMapper().readTree(result.getResponse().getContentAsString())
                .at("/data/accessToken").asText();
    }}
