package com.artacademy.product;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.artacademy.course.CourseKitRepository;
import com.artacademy.course.CourseRepository;
import com.artacademy.supply.catalog.SupplyProductRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Cầu nối B ↔ E: quà tặng kèm tra thẳng bảng products của E (không còn danh mục tạm trong bộ nhớ).
 * Dùng dữ liệu mẫu thật của E (PEN-2B-12, WC-24-PAN...) nên khóa/combo mẫu có quà phải ở trạng thái "còn quà".
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:artnest_bridge;MODE=MySQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "app.products.stand-in=false",
        "app.seed.enabled=true",
        "app.upload.dir=target/test-uploads"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SupplyProductBridgeTest {

    @Autowired MockMvc mvc;
    @Autowired ProductCatalog catalog;
    @Autowired SupplyProductRepository supplyProducts;
    @Autowired CourseRepository courses;
    @Autowired CourseKitRepository kits;

    @Test
    void catalogIsTheSupplyBackedOne_andFindsRealProducts() {
        assertTrue(catalog instanceof SupplyProductCatalog, catalog.getClass().getName());

        Product pencil = supplyProducts.findAll().stream().filter(p -> p.getSku().equals("PEN-2B-12")).findFirst()
                .orElseThrow();
        Map<Integer, ProductCatalog.KitProduct> found = catalog.findByIds(List.of(pencil.getId().intValue(), 999_999));

        assertEquals(1, found.size());
        ProductCatalog.KitProduct kit = found.get(pencil.getId().intValue());
        assertEquals("PEN-2B-12", kit.sku());
        assertEquals(pencil.getName(), kit.name());
        assertTrue(kit.active());
        assertTrue(catalog.findByIds(List.of()).isEmpty());
    }

    @Test
    void sampleCourseWithGifts_hasAvailableKits_soItIsNotBlockedByAMissingCatalog() {
        var watercolor = courses.findAll().stream().filter(c -> c.getCode().equals("KH-WATERCOLOR")).findFirst()
                .orElseThrow();

        var attached = kits.findByCourse(watercolor.getId());
        assertEquals(2, attached.size());
        assertTrue(catalog.findByIds(attached.stream().map(k -> k.getProductId()).toList()).values().stream()
                .allMatch(ProductCatalog.KitProduct::active));
    }

    @Test
    void adminProductList_comesFromTheSupplyTable_andOnlyActiveProducts() throws Exception {
        mvc.perform(get("/api/admin/products").with(user("admin@test.local").roles("ADMIN")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[?(@.sku=='WC-24-PAN')]").exists());
        mvc.perform(get("/api/admin/products").with(user("hocvien@test.local").roles("USER")))
                .andExpect(status().isForbidden());
        assertFalse(supplyProducts.findAll().isEmpty());
    }
}
