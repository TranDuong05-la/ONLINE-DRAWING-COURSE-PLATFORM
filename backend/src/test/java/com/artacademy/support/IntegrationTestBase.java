package com.artacademy.support;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

import com.artacademy.bundle.BundleItemRepository;
import com.artacademy.bundle.BundleRepository;
import com.artacademy.course.Course;
import com.artacademy.course.CourseIncludedKit;
import com.artacademy.course.CourseKitRepository;
import com.artacademy.course.CourseLesson;
import com.artacademy.course.CourseLessonRepository;
import com.artacademy.course.CourseLevel;
import com.artacademy.course.CourseModule;
import com.artacademy.course.CourseModuleRepository;
import com.artacademy.course.CourseRepository;
import com.artacademy.course.CourseStatus;
import com.artacademy.product.Product;
import com.artacademy.product.ProductRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.UUID;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;

/**
 * Lớp nền cho test tích hợp: chạy cả ứng dụng với H2 trong bộ nhớ (không cần cài MySQL), có Spring Security thật.
 * Các API /api/admin/** cần quyền ADMIN nên test gọi kèm {@link #admin()}; API công khai (GET khóa học, combo) gọi không đăng nhập.
 *
 * Các test KHÔNG dùng @Transactional: mỗi request MockMvc chạy trong transaction riêng như khi chạy thật,
 * nên việc "bị từ chối thì hoàn tác" được kiểm tra đúng. Thay vào đó, trước mỗi test xóa sạch dữ liệu.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:artnest_course_test;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.seed.enabled=false",
        "app.upload.dir=target/test-uploads"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTestBase {

    protected static final String VIDEO = "https://example.com/video.mp4";

    @Autowired protected MockMvc mvc;
    @Autowired protected ObjectMapper json;
    @Autowired protected CourseRepository courseRepository;
    @Autowired protected CourseModuleRepository moduleRepository;
    @Autowired protected CourseLessonRepository lessonRepository;
    @Autowired protected CourseKitRepository kitRepository;
    @Autowired protected ProductRepository productRepository;
    @Autowired protected BundleRepository bundleRepository;
    @Autowired protected BundleItemRepository bundleItemRepository;

    @BeforeEach
    void cleanDatabase() {
        // Xóa theo thứ tự từ bảng con đến bảng cha để không vi phạm khóa ngoại
        bundleItemRepository.deleteAll();
        bundleRepository.deleteAll();
        kitRepository.deleteAll();
        lessonRepository.deleteAll();
        moduleRepository.deleteAll();
        courseRepository.deleteAll();
        productRepository.deleteAll();
    }

    // ------------------------------------------------------------------ dữ liệu mẫu

    protected Course newCourse(String title, long price, Long discount, CourseLevel level, CourseStatus status) {
        Course c = new Course();
        c.setCode("T-" + UUID.randomUUID().toString().substring(0, 8));
        c.setSlug("slug-" + UUID.randomUUID());
        c.setTitle(title);
        c.setShortDescription("Mô tả ngắn của " + title);
        c.setPrice(price);
        c.setDiscountPrice(discount);
        c.setLevel(level);
        c.setStatus(status);
        if (status != CourseStatus.DRAFT) {
            c.setPublishedAt(LocalDateTime.now());
        }
        return courseRepository.save(c);
    }

    protected CourseModule newModule(Course course, String title, int order, boolean hidden) {
        CourseModule m = new CourseModule(course, title, order);
        m.setHidden(hidden);
        return moduleRepository.save(m);
    }

    protected CourseLesson newLesson(CourseModule module, String title, int order, String videoUrl,
                                     boolean preview, boolean hidden) {
        CourseLesson l = new CourseLesson(module, title, order);
        l.setVideoUrl(videoUrl);
        l.setDurationSeconds(60);
        l.setPreview(preview);
        l.setHidden(hidden);
        return lessonRepository.save(l);
    }

    /** Khóa đã xuất bản, có 1 module và 1 bài có video: đủ điều kiện bán. */
    protected Course publishedCourse(String title, long price, Long discount, CourseLevel level) {
        Course c = newCourse(title, price, discount, level, CourseStatus.PUBLISHED);
        CourseModule m = newModule(c, "Module 1", 1, false);
        newLesson(m, "Bài 1", 1, VIDEO, false, false);
        return c;
    }

    protected Product newProduct(String sku, boolean active) {
        Product p = new Product(sku, "Sản phẩm " + sku, 10_000, null);
        p.setActive(active);
        return productRepository.save(p);
    }

    protected CourseIncludedKit newKit(Course course, Product product, int quantity) {
        return kitRepository.save(new CourseIncludedKit(course, product, quantity));
    }

    // ------------------------------------------------------------------ gọi API

    /** Người dùng đăng nhập giả với role ADMIN. */
    protected static RequestPostProcessor admin() {
        return user("admin@test.local").roles("ADMIN");
    }


    protected ResultActions postJson(String url, Object body) throws Exception {
        return mvc.perform(post(url).with(admin()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    protected ResultActions putJson(String url, Object body) throws Exception {
        return mvc.perform(put(url).with(admin()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    protected ResultActions patchJson(String url, Object body) throws Exception {
        return mvc.perform(patch(url).with(admin()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body)));
    }

    protected ResultActions getJson(String url) throws Exception {
        // /api/admin/** cần ADMIN; các URL còn lại gọi như người dùng chưa đăng nhập
        return mvc.perform(url.startsWith("/api/admin") ? get(url).with(admin()) : get(url));
    }

    /** Lấy 1 giá trị số (ví dụ id) từ JSON trả về theo đường dẫn JSON Pointer như "/course/id". */
    /** Dữ liệu nằm trong trường "data" của ApiResponse nên tự thêm tiền tố /data. */
    protected long readLong(MvcResult result, String pointer) throws Exception {
        return json.readTree(result.getResponse().getContentAsString()).at("/data" + pointer).asLong();
    }
}
