package com.artacademy.supply.dev;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.artacademy.supply.catalog.CatalogDtos.CategoryRequest;
import com.artacademy.supply.catalog.CatalogDtos.ProductRequest;
import com.artacademy.supply.catalog.CatalogService;
import com.artacademy.supply.catalog.ProductCategoryRepository;
import com.artacademy.supply.catalog.SupplyProductRepository;

/**
 * Dữ liệu mẫu học cụ cho demo (giống DataInitializer của A: tắt bằng SEED_DATA_ENABLED=false, không chạy ở prod).
 * Chỉ chạy khi chưa có danh mục nào. Bỏ qua SKU đã có (vd. B đã tạo sản phẩm để làm quà).
 */
@Component
@Profile("!prod")
// Chạy trước CourseDataSeeder của B để quà mẫu của khóa học tìm được sản phẩm theo SKU
@Order(Ordered.LOWEST_PRECEDENCE - 10)
public class SupplySeeder implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(SupplySeeder.class);

    private final ProductCategoryRepository categoryRepository;
    private final SupplyProductRepository productRepository;
    private final CatalogService catalogService;

    @Value("${app.seed.enabled:true}")
    private boolean seedEnabled;

    public SupplySeeder(ProductCategoryRepository categoryRepository, SupplyProductRepository productRepository,
            CatalogService catalogService) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.catalogService = catalogService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!seedEnabled || categoryRepository.count() > 0) {
            return;
        }
        Long pencil = cat("Bút chì & than", "Bút chì phác thảo, than vẽ", 1);
        Long water = cat("Màu vẽ", "Màu nước, màu acrylic, màu sáp", 2);
        Long paper = cat("Giấy vẽ", "Giấy màu nước, sketchbook", 3);
        Long brush = cat("Cọ vẽ", "Cọ tròn, cọ dẹt, cọ nước", 4);

        product(pencil, "PEN-2B-12", "Bộ bút chì phác thảo 12 cây", 95_000, 40,
                "Bộ 12 cây từ 6H đến 8B, phù hợp phác thảo và đánh bóng.",
                "https://images.unsplash.com/photo-1513364776144-60967b0f800f?auto=format&fit=crop&w=600&q=80");
        product(water, "WC-24-PAN", "Hộp màu nước 24 màu", 320_000, 15,
                "Màu nước dạng nén 24 ô kèm cọ nước, màu tươi, dễ tán.",
                "https://images.unsplash.com/photo-1460661419201-fd4cecdf8a8b?auto=format&fit=crop&w=600&q=80");
        product(paper, "PAP-A4-300", "Sổ vẽ màu nước A4 300gsm", 185_000, 3,
                "Giấy cotton, vân nhám vừa, không nhăn khi thấm nước. Sắp hết hàng để thử cảnh báo tồn.",
                "https://images.unsplash.com/photo-1544816155-12df9643f363?auto=format&fit=crop&w=600&q=80");
        product(brush, "BR-SET-6", "Bộ cọ vẽ tổng hợp 6 cây", 149_000, 1,
                "Chỉ còn 1 bộ, dùng để thử trường hợp hai người cùng mua món cuối.",
                "https://images.unsplash.com/photo-1596548438137-d51ea5c83ca5?auto=format&fit=crop&w=600&q=80");
        product(brush, "BR-RD-8", "Cọ tròn lông sóc số 8", 260_000, 0,
                "Đang hết hàng.",
                "https://images.unsplash.com/photo-1579762715118-a6f1d4b934f1?auto=format&fit=crop&w=600&q=80");
        product(pencil, "KIT-SKETCH", "Bộ quà phác thảo cơ bản", 120_000, 30,
                "Bộ học cụ để B gắn làm quà kèm khóa học phác thảo (vẫn bán lẻ được).",
                "https://images.unsplash.com/photo-1452860606245-08befc0ff44b?auto=format&fit=crop&w=600&q=80");
        log.info("Đã tạo dữ liệu mẫu học cụ (phần E)");
    }

    private Long cat(String name, String desc, int order) {
        return catalogService.createCategory(new CategoryRequest(name, desc, true, order)).id();
    }

    private void product(Long categoryId, String sku, String name, long price, int stock, String desc, String image) {
        if (productRepository.existsBySkuIgnoreCase(sku)) {
            return;
        }
        catalogService.createProduct(new ProductRequest(categoryId, sku, name, desc, price, image, 5, stock, true),
                null);
    }
}
