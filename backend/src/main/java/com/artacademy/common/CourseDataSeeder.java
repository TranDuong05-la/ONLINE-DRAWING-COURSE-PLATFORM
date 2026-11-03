package com.artacademy.common;

import com.artacademy.bundle.BundleItem;
import com.artacademy.bundle.BundleItemRepository;
import com.artacademy.bundle.BundleRepository;
import com.artacademy.bundle.CourseBundle;
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
import java.time.LocalDateTime;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thêm dữ liệu mẫu khi bảng còn trống (chạy mỗi lần khởi động nhưng chỉ thêm nếu chưa có gì).
 * Tắt bằng app.seed.enabled=false. Video là video mẫu công khai, nhóm thay bằng video thật qua màn admin.
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
public class CourseDataSeeder implements CommandLineRunner {

    private static final String VIDEO_1 = "https://flutter.github.io/assets-for-api-docs/assets/videos/butterfly.mp4";
    private static final String VIDEO_2 = "https://flutter.github.io/assets-for-api-docs/assets/videos/bee.mp4";

    private final ProductRepository productRepository;
    private final CourseRepository courseRepository;
    private final CourseModuleRepository moduleRepository;
    private final CourseLessonRepository lessonRepository;
    private final CourseKitRepository kitRepository;
    private final BundleRepository bundleRepository;
    private final BundleItemRepository bundleItemRepository;

    public CourseDataSeeder(ProductRepository productRepository, CourseRepository courseRepository,
                      CourseModuleRepository moduleRepository, CourseLessonRepository lessonRepository,
                      CourseKitRepository kitRepository, BundleRepository bundleRepository,
                      BundleItemRepository bundleItemRepository) {
        this.productRepository = productRepository;
        this.courseRepository = courseRepository;
        this.moduleRepository = moduleRepository;
        this.lessonRepository = lessonRepository;
        this.kitRepository = kitRepository;
        this.bundleRepository = bundleRepository;
        this.bundleItemRepository = bundleItemRepository;
    }

    @Override
    @Transactional
    public void run(String... args) {
        if (productRepository.count() == 0) {
            seedProducts();
        }
        if (courseRepository.count() == 0) {
            seedCourses();
        }
    }

    private void seedProducts() {
        productRepository.save(new Product("BM-WC12", "Bộ màu nước 12 màu", 150_000, null));
        productRepository.save(new Product("CO-SET3", "Bộ cọ vẽ 3 cây", 90_000, null));
        productRepository.save(new Product("GV-A4-20", "Giấy vẽ A4 (xấp 20 tờ)", 60_000, null));
        productRepository.save(new Product("BC-SK6", "Bộ bút chì phác thảo 6 cây", 80_000, null));
        productRepository.save(new Product("PL-MIX", "Palette pha màu", 45_000, null));
    }

    private void seedCourses() {
        // Khóa 1: có dụng cụ tặng kèm, không giảm giá
        Course watercolor = newCourse("KH-WATERCOLOR", "Vẽ màu nước cho người mới bắt đầu",
                "Làm quen màu nước từ con số 0: cách pha màu, đi cọ và vẽ tranh đầu tiên.",
                "Khóa học hướng dẫn từng bước cho thanh thiếu niên mới bắt đầu: chọn dụng cụ, pha màu, "
                        + "kỹ thuật loang màu, đi nét và hoàn thiện một bức tranh phong cảnh đơn giản.",
                450_000, null, CourseLevel.BASIC, CourseStatus.PUBLISHED);
        CourseModule w1 = module(watercolor, "Làm quen dụng cụ", 1);
        lesson(w1, "Giới thiệu khóa học", "Bạn sẽ học gì trong khóa này.", VIDEO_1, 7, 1, true, false);
        lesson(w1, "Các dụng cụ cần có", "Màu, cọ, giấy và palette.", VIDEO_2, 8, 2, false, true);
        CourseModule w2 = module(watercolor, "Kỹ thuật cơ bản", 2);
        lesson(w2, "Kỹ thuật loang màu", "Loang màu ướt trên ướt.", VIDEO_1, 7, 1, false, true);
        kit(watercolor, "BM-WC12", 1);
        kit(watercolor, "CO-SET3", 1);

        // Khóa 2: nâng cao, có giá giảm, không có quà
        Course portrait = newCourse("KH-PORTRAIT", "Phác thảo chân dung nâng cao",
                "Tỉ lệ khuôn mặt, đổ bóng và biểu cảm trong tranh chân dung bằng chì.",
                "Dành cho bạn đã quen cầm bút chì: học tỉ lệ khuôn mặt, đổ bóng khối, vẽ mắt - mũi - miệng "
                        + "và bắt biểu cảm. (Người mới vẫn có thể học khóa này.)",
                600_000, 449_000L, CourseLevel.ADVANCED, CourseStatus.PUBLISHED);
        CourseModule p1 = module(portrait, "Tỉ lệ khuôn mặt", 1);
        lesson(p1, "Chia tỉ lệ khuôn mặt", "Các đường chia chuẩn.", VIDEO_2, 8, 1, true, true);
        lesson(p1, "Vẽ mắt và mũi", "Chi tiết từng bộ phận.", VIDEO_1, 7, 2, false, true);
        lesson(p1, "Đổ bóng khối", "Chuyển sắc độ mượt.", VIDEO_2, 8, 3, false, false);

        // Khóa 3: nháp (chỉ admin thấy), chưa có video nên chưa xuất bản được
        Course draft = newCourse("KH-LANDSCAPE", "Vẽ phong cảnh bằng chì màu",
                "Khóa đang soạn.", null, 350_000, null, CourseLevel.BASIC, CourseStatus.DRAFT);
        CourseModule d1 = module(draft, "Giới thiệu", 1);
        lesson(d1, "Bài mở đầu", null, null, 0, 1, false, true);

        // Combo mẫu gồm 2 khóa đã xuất bản (tổng thực bán 899.000 > giá combo)
        CourseBundle bundle = new CourseBundle();
        bundle.setCode("CB-COMBO-1");
        bundle.setTitle("Combo Màu nước + Chân dung");
        bundle.setDescription("Học trọn bộ kỹ thuật màu nước và phác thảo chân dung với giá ưu đãi.");
        bundle.setPrice(799_000);
        bundleRepository.save(bundle);
        bundleItemRepository.save(new BundleItem(bundle, watercolor));
        bundleItemRepository.save(new BundleItem(bundle, portrait));
    }

    private Course newCourse(String code, String title, String shortDesc, String description, long price,
                             Long discountPrice, CourseLevel level, CourseStatus status) {
        Course c = new Course();
        c.setCode(code);
        c.setSlug(TextUtils.slugify(title));
        c.setTitle(title);
        c.setShortDescription(shortDesc);
        c.setDescription(description);
        c.setPrice(price);
        c.setDiscountPrice(discountPrice);
        c.setLevel(level);
        c.setStatus(status);
        if (status == CourseStatus.PUBLISHED) {
            c.setPublishedAt(LocalDateTime.now());
        }
        return courseRepository.save(c);
    }

    private CourseModule module(Course course, String title, int order) {
        return moduleRepository.save(new CourseModule(course, title, order));
    }

    /** Thời lượng là số giây ước lượng của video mẫu. */
    private void lesson(CourseModule module, String title, String description, String videoUrl, int seconds,
                        int order, boolean preview, boolean required) {
        CourseLesson l = new CourseLesson(module, title, order);
        l.setDescription(description);
        l.setVideoUrl(videoUrl);
        l.setDurationSeconds(seconds);
        l.setPreview(preview);
        l.setRequired(required);
        lessonRepository.save(l);
    }

    private void kit(Course course, String sku, int quantity) {
        Product product = productRepository.findAll().stream()
                .filter(p -> p.getSku().equals(sku)).findFirst().orElseThrow();
        kitRepository.save(new CourseIncludedKit(course, product, quantity));
    }
}
