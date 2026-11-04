package com.artacademy.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.artacademy.entity.User;
import com.artacademy.enums.Role;
import com.artacademy.repository.UserRepository;
import com.artacademy.support.IntegrationTestBase;
import com.drawingcourse.backend.entity.Order;
import com.drawingcourse.backend.entity.OrderItemType;
import com.drawingcourse.backend.entity.OrderJpaEntity;
import com.drawingcourse.backend.entity.OrderLineSnapshot;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Các lỗi member báo: dashboard admin gán cứng 0, PATCH trạng thái lỗi khi kèm reason, trùng tên schema Swagger. */
class AdminAndAccountFixesTest extends IntegrationTestBase {

    @Autowired AdminCatalogMetrics metrics;
    @Autowired UserRepository users;
    @Autowired EntityManager em;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager transactions;

    private User newUser() {
        return users.saveAndFlush(User.builder().fullName("Fix test")
                .email("fix-" + UUID.randomUUID() + "@example.test").passwordHash("hash")
                .role(Role.USER).isActive(true).build());
    }

    private Integer newOrder(User user, long price) {
        Order snapshot = Order.create(user.getId(), List.of(new OrderLineSnapshot(OrderItemType.COURSE,
                1000 + (int) (Math.random() * 100000), "Course", 1, price, true)), 0, Optional.empty(),
                Clock.fixed(Instant.now(), ZoneOffset.UTC));
        return new TransactionTemplate(transactions).execute(s -> {
            OrderJpaEntity row = OrderJpaEntity.fromSnapshot(snapshot);
            em.persist(row);
            em.flush();
            return row.getId();
        });
    }

    // ------------------------------------------------------------------ dashboard admin

    @Test
    void dashboard_countsRealCoursesOrdersAndOnlyPaidRevenue() throws Exception {
        long courses0 = metrics.totalCourses();
        long orders0 = metrics.totalOrders();
        BigDecimal revenue0 = metrics.totalRevenue();
        newCourse("Khóa A", 100_000, null, com.artacademy.course.CourseLevel.BASIC, com.artacademy.course.CourseStatus.PUBLISHED);
        newCourse("Khóa B", 200_000, null, com.artacademy.course.CourseLevel.BASIC, com.artacademy.course.CourseStatus.DRAFT);
        User buyer = newUser();
        Integer paid = newOrder(buyer, 300_000);
        Integer completed = newOrder(buyer, 50_000);
        newOrder(buyer, 999_000); // chưa thanh toán: không tính vào doanh thu
        jdbc.update("UPDATE orders SET status='CONFIRMED' WHERE id=?", paid);
        jdbc.update("UPDATE orders SET status='COMPLETED' WHERE id=?", completed);

        assertThat(metrics.totalCourses()).isEqualTo(courses0 + 2);
        assertThat(metrics.totalOrders()).isEqualTo(orders0 + 3);
        assertThat(metrics.totalRevenue()).isEqualByComparingTo(revenue0.add(new BigDecimal("350000")));

        // API dashboard trả đúng các số đó
        mvc.perform(get("/api/admin/dashboard").with(admin()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalCourses").value(courses0 + 2))
                .andExpect(jsonPath("$.data.totalOrders").value(orders0 + 3))
                .andExpect(jsonPath("$.data.totalRevenue").value(revenue0.add(new BigDecimal("350000")).longValue()));
    }

    // ------------------------------------------------------------------ PATCH trạng thái tài khoản

    @Test
    void patchUserStatus_acceptsAReason_likePut() throws Exception {
        User target = newUser();

        mvc.perform(patch("/api/admin/users/" + target.getId() + "/status").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"isActive\":false,\"reason\":\"Vi phạm quy định\"}"))
                .andExpect(status().isOk());
        assertThat(users.findById(target.getId()).orElseThrow().getIsActive()).isFalse();

        mvc.perform(patch("/api/admin/users/" + target.getId() + "/status").with(admin())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"ACTIVE\",\"reason\":\"Đã giải quyết\"}"))
                .andExpect(status().isOk());
        assertThat(users.findById(target.getId()).orElseThrow().getIsActive()).isTrue();

        // thiếu cả isActive lẫn status vẫn bị từ chối rõ ràng
        mvc.perform(patch("/api/admin/users/" + target.getId() + "/status").with(admin())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"không rõ\"}"))
                .andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ Swagger

    @Test
    void openApi_hasSeparateSchemasForSubmissionAndProductReviewRequests() throws Exception {
        MvcResult result = mvc.perform(get("/v3/api-docs").with(admin())).andExpect(status().isOk()).andReturn();
        var schemas = json.readTree(result.getResponse().getContentAsString()).at("/components/schemas");

        assertThat(schemas.has("SubmissionReviewRequest")).isTrue();
        assertThat(schemas.has("ProductReviewRequest")).isTrue();
        // chấm bài có "decision", đánh giá học cụ có số sao: hai mẫu khác nhau, không đè lên nhau
        assertThat(schemas.at("/SubmissionReviewRequest/properties").has("decision")).isTrue();
        assertThat(schemas.at("/ProductReviewRequest/properties").has("decision")).isFalse();
        assertThat(schemas.at("/ProductReviewRequest/properties").has("rating")).isTrue();
        assertThat(schemas.has("ReviewRequest")).isFalse();
    }
}
