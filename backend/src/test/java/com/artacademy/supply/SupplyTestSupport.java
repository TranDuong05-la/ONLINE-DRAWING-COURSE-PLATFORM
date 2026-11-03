package com.artacademy.supply;

import java.util.concurrent.atomic.AtomicInteger;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;

import com.artacademy.enums.Role;
import com.artacademy.supply.catalog.CatalogDtos.CategoryRequest;
import com.artacademy.supply.catalog.CatalogDtos.ProductRequest;
import com.artacademy.supply.catalog.CatalogDtos.ProductResponse;
import com.artacademy.supply.catalog.CatalogService;
import com.artacademy.supply.common.SupplyUser;

/**
 * Dùng H2 riêng có LOCK_TIMEOUT dài để test nhiều người cùng giữ hàng không bị lỗi khóa giả.
 */
@SpringBootTest
@ActiveProfiles("test")
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:supplytest;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=false;LOCK_TIMEOUT=10000"
})
abstract class SupplyTestSupport {

    private static final AtomicInteger SEQ = new AtomicInteger(100_000);

    protected static final SupplyUser STAFF = new SupplyUser(1, Role.STAFF);

    @Autowired
    protected CatalogService catalogService;

    protected static int nextId() {
        return SEQ.incrementAndGet();
    }

    protected static SupplyUser student() {
        return new SupplyUser(nextId(), Role.USER);
    }

    protected Long newProduct(long price, int stock) {
        return newProduct(price, stock, true);
    }

    protected Long newProduct(long price, int stock, boolean active) {
        int n = nextId();
        Long categoryId = catalogService.createCategory(new CategoryRequest("Cat " + n, null, true, 0)).id();
        ProductResponse p = catalogService.createProduct(new ProductRequest(categoryId, "SKU-" + n,
                "Học cụ " + n, null, price, null, 2, stock, active), STAFF.id());
        return p.id();
    }

    protected ProductResponse stock(Long productId) {
        return catalogService.managerDetail(productId);
    }
}
