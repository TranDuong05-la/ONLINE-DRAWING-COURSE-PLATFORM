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
import com.artacademy.product.InMemoryProductCatalog;
import com.artacademy.supply.catalog.SupplyProductRepository;
import java.time.LocalDateTime;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thêm dữ liệu mẫu khi bảng còn trống (chạy mỗi lần khởi động nhưng chỉ thêm nếu chưa có gì).
 * Tắt bằng app.seed.enabled=false. Video là video mẫu công khai, nhóm thay bằng video thật qua màn admin.
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true")
// Chạy sau SupplySeeder của E (Ordered.LOWEST_PRECEDENCE - 10) để quà mẫu tìm được sản phẩm thật theo SKU
@Order(Ordered.LOWEST_PRECEDENCE)
public class CourseDataSeeder implements CommandLineRunner {

    private static final String VIDEO_1 = "https://flutter.github.io/assets-for-api-docs/assets/videos/butterfly.mp4";
    private static final String VIDEO_2 = "https://flutter.github.io/assets-for-api-docs/assets/videos/bee.mp4";

    /** Danh mục sản phẩm tạm của B; vắng mặt khi dùng sản phẩm thật của E (app.products.stand-in=false, mặc định). */
    private final ObjectProvider<InMemoryProductCatalog> standInCatalog;
    /** Kho sản phẩm thật của E: quà mẫu gắn theo SKU khi không có danh mục tạm. */
    private final SupplyProductRepository supplyProducts;
    private final CourseRepository courseRepository;
    private final CourseModuleRepository moduleRepository;
    private final CourseLessonRepository lessonRepository;
    private final CourseKitRepository kitRepository;
    private final BundleRepository bundleRepository;
    private final BundleItemRepository bundleItemRepository;

    public CourseDataSeeder(ObjectProvider<InMemoryProductCatalog> standInCatalog,
                      SupplyProductRepository supplyProducts, CourseRepository courseRepository,
                      CourseModuleRepository moduleRepository, CourseLessonRepository lessonRepository,
                      CourseKitRepository kitRepository, BundleRepository bundleRepository,
                      BundleItemRepository bundleItemRepository) {
        this.standInCatalog = standInCatalog;
        this.supplyProducts = supplyProducts;
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
        // Sản phẩm mẫu nằm trong bộ nhớ nên phải nạp lại mỗi lần chạy (khóa học thì chỉ nạp khi bảng còn trống)
        standInCatalog.ifAvailable(catalog -> {
            if (catalog.isEmpty()) {
                seedProducts(catalog);
            }
        });
        if (courseRepository.count() == 0) {
            seedCourses();
        }
    }

    private void seedProducts(InMemoryProductCatalog catalog) {
        catalog.add("BM-WC12", "Bộ màu nước 12 màu", 150_000, true);
        catalog.add("CO-SET3", "Bộ cọ vẽ 3 cây", 90_000, true);
        catalog.add("GV-A4-20", "Giấy vẽ A4 (xấp 20 tờ)", 60_000, true);
        catalog.add("BC-SK6", "Bộ bút chì phác thảo 6 cây", 80_000, true);
        catalog.add("PL-MIX", "Palette pha màu", 45_000, true);
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
        // SKU của danh mục tạm (BM-WC12, CO-SET3) hoặc của E (WC-24-PAN, PAP-A4-300); chỉ cái nào có mới được gắn
        kit(watercolor, "BM-WC12", "WC-24-PAN", 1);
        kit(watercolor, "CO-SET3", "PAP-A4-300", 1);

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

        // Khóa 4: cơ bản, không có quà tặng kèm (để thử mua combo trong luồng chỉ bán khóa học)
        Course sketch = newCourse("KH-SKETCH", "Ký họa nhanh mỗi ngày",
                "Luyện tay với những bài ký họa 10 phút: nét, khối và bố cục.",
                "Mỗi ngày một bài ký họa ngắn giúp bạn quen tay, quen mắt: nét thẳng, nét cong, chia khối cơ bản "
                        + "và bố cục một bức tranh nhỏ.",
                300_000, null, CourseLevel.BASIC, CourseStatus.PUBLISHED);
        CourseModule s1 = module(sketch, "Làm quen ký họa", 1);
        lesson(s1, "Cầm bút và luyện nét", "Nét thẳng, nét cong.", VIDEO_1, 7, 1, true, true);
        lesson(s1, "Chia khối cơ bản", "Hình khối từ hình đơn giản.", VIDEO_2, 8, 2, false, true);

        // Combo mẫu gồm 2 khóa đã xuất bản (tổng thực bán 899.000 > giá combo)
        CourseBundle bundle = new CourseBundle();
        bundle.setCode("CB-COMBO-1");
        bundle.setTitle("Combo Màu nước + Chân dung");
        bundle.setDescription("Học trọn bộ kỹ thuật màu nước và phác thảo chân dung với giá ưu đãi.");
        bundle.setPrice(799_000);
        bundleRepository.save(bundle);
        bundleItemRepository.save(new BundleItem(bundle, watercolor, 1));
        bundleItemRepository.save(new BundleItem(bundle, portrait, 2));

        // Combo mẫu 2: hai khóa không có quà tặng kèm nên mua được ngay trong luồng chỉ bán khóa học
        // (tổng thực bán 749.000 > giá combo)
        CourseBundle noKitBundle = new CourseBundle();
        noKitBundle.setCode("CB-COMBO-2");
        noKitBundle.setTitle("Combo Chân dung + Ký họa");
        noKitBundle.setDescription("Luyện tay với ký họa rồi nâng lên chân dung, giá ưu đãi cho cả hai khóa.");
        noKitBundle.setPrice(649_000);
        bundleRepository.save(noKitBundle);
        bundleItemRepository.save(new BundleItem(noKitBundle, portrait, 1));
        bundleItemRepository.save(new BundleItem(noKitBundle, sketch, 2));
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

    private void kit(Course course, String standInSku, String supplySku, int quantity) {
        InMemoryProductCatalog catalog = standInCatalog.getIfAvailable();
        if (catalog != null) {
            var product = catalog.findBySku(standInSku).orElseThrow();
            kitRepository.save(new CourseIncludedKit(course, product.id(), quantity));
            return;
        }
        // Dùng sản phẩm thật của E: nếu E chưa nạp sản phẩm mẫu thì không gắn quà (admin tự chọn ở màn quản trị)
        supplyProducts.findAll().stream().filter(p -> p.getSku().equalsIgnoreCase(supplySku)).findFirst()
                .ifPresent(p -> kitRepository.save(new CourseIncludedKit(course, p.getId().intValue(), quantity)));
    }
}
