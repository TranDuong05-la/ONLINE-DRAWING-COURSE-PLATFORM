package com.artacademy.supply.catalog;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.artacademy.product.Product;
import com.artacademy.product.ProductCategory;
import com.artacademy.supply.catalog.CatalogDtos.CategoryRequest;
import com.artacademy.supply.catalog.CatalogDtos.CategoryResponse;
import com.artacademy.supply.catalog.CatalogDtos.InventoryTransactionResponse;
import com.artacademy.supply.catalog.CatalogDtos.ProductRequest;
import com.artacademy.supply.catalog.CatalogDtos.ProductResponse;
import com.artacademy.supply.common.PageResult;
import com.artacademy.supply.common.SupplyException;
import com.artacademy.supply.inventory.InventoryTransactionRepository;
import com.artacademy.supply.inventory.StockLedger;

@Service
public class CatalogService {

    private final ProductCategoryRepository categoryRepository;
    private final SupplyProductRepository productRepository;
    private final InventoryTransactionRepository transactionRepository;
    private final StockLedger ledger;

    public CatalogService(ProductCategoryRepository categoryRepository, SupplyProductRepository productRepository,
            InventoryTransactionRepository transactionRepository, StockLedger ledger) {
        this.categoryRepository = categoryRepository;
        this.productRepository = productRepository;
        this.transactionRepository = transactionRepository;
        this.ledger = ledger;
    }

    // ---------- Học viên ----------

    @Transactional(readOnly = true)
    public List<CategoryResponse> activeCategories() {
        return categoryRepository.findByActiveTrueOrderBySortOrderAscNameAsc().stream()
                .map(CategoryResponse::of).toList();
    }

    @Transactional(readOnly = true)
    public PageResult<ProductResponse> searchShop(String q, Long categoryId, Boolean inStock, Long minPrice,
            Long maxPrice, String sort, int page, int size) {
        Specification<Product> spec = and(ProductSpecs.fetchCategory(),
                ProductSpecs.active(true),
                ProductSpecs.categoryVisible(),
                ProductSpecs.keyword(q),
                ProductSpecs.category(categoryId),
                ProductSpecs.inStock(inStock),
                ProductSpecs.priceBetween(minPrice, maxPrice));
        return PageResult.of(productRepository.findAll(spec, pageable(page, size, sort)), ProductResponse::forShop);
    }

    @Transactional(readOnly = true)
    public ProductResponse shopDetail(Long id) {
        Product p = productRepository.findById(id)
                .filter(x -> x.isActive() && (x.getCategory() == null || x.getCategory().isActive()))
                .orElseThrow(() -> SupplyException.notFound("Học cụ"));
        return ProductResponse.forShop(p);
    }

    // ---------- Admin: danh mục ----------

    @Transactional(readOnly = true)
    public List<CategoryResponse> allCategories() {
        return categoryRepository.findAllByOrderBySortOrderAscNameAsc().stream().map(CategoryResponse::of).toList();
    }

    @Transactional
    public CategoryResponse createCategory(CategoryRequest req) {
        String name = req.name().trim();
        if (categoryRepository.existsByNameIgnoreCase(name)) {
            throw SupplyException.conflict("CATEGORY_EXISTS", "Danh mục \"" + name + "\" đã tồn tại");
        }
        ProductCategory c = new ProductCategory();
        applyCategory(c, req);
        return CategoryResponse.of(categoryRepository.save(c));
    }

    @Transactional
    public CategoryResponse updateCategory(Long id, CategoryRequest req) {
        ProductCategory c = categoryRepository.findById(id)
                .orElseThrow(() -> SupplyException.notFound("Danh mục"));
        if (categoryRepository.existsByNameIgnoreCaseAndIdNot(req.name().trim(), id)) {
            throw SupplyException.conflict("CATEGORY_EXISTS", "Tên danh mục đã được dùng");
        }
        applyCategory(c, req);
        categoryRepository.flush();
        return CategoryResponse.of(c);
    }

    private static void applyCategory(ProductCategory c, CategoryRequest req) {
        c.setName(req.name().trim());
        c.setDescription(blankToNull(req.description()));
        if (req.active() != null) {
            c.setActive(req.active());
        }
        if (req.sortOrder() != null) {
            c.setSortOrder(req.sortOrder());
        }
    }

    // ---------- Quản lý: sản phẩm và kho ----------

    @Transactional(readOnly = true)
    public PageResult<ProductResponse> searchManager(String q, Long categoryId, Boolean active, Boolean lowStock,
            String sort, int page, int size) {
        Specification<Product> spec = and(ProductSpecs.fetchCategory(),
                ProductSpecs.keyword(q),
                ProductSpecs.category(categoryId),
                ProductSpecs.active(active),
                ProductSpecs.lowStock(lowStock));
        return PageResult.of(productRepository.findAll(spec, pageable(page, size, sort)),
                ProductResponse::forManager);
    }

    @Transactional(readOnly = true)
    public ProductResponse managerDetail(Long id) {
        return ProductResponse.forManager(findProduct(id));
    }

    @Transactional
    public ProductResponse createProduct(ProductRequest req, Integer actorId) {
        String sku = req.sku().trim().toUpperCase();
        if (productRepository.existsBySkuIgnoreCase(sku)) {
            throw SupplyException.conflict("SKU_EXISTS", "SKU " + sku + " đã tồn tại");
        }
        Product p = new Product(sku, req.name().trim(), req.price(), blankToNull(req.thumbnailUrl()));
        applyProduct(p, req);
        Long id = productRepository.saveAndFlush(p).getId();
        if (req.initialStock() != null && req.initialStock() > 0) {
            ledger.importStock(id, req.initialStock(), "Tồn đầu khi tạo sản phẩm", actorId);
        }
        return ProductResponse.forManager(findProduct(id));
    }

    @Transactional
    public ProductResponse updateProduct(Long id, ProductRequest req) {
        Product p = findProduct(id);
        String sku = req.sku().trim().toUpperCase();
        if (productRepository.existsBySkuIgnoreCaseAndIdNot(sku, id)) {
            throw SupplyException.conflict("SKU_EXISTS", "SKU " + sku + " đã được dùng");
        }
        p.setSku(sku);
        p.setName(req.name().trim());
        p.setPrice(req.price());
        p.setThumbnailUrl(blankToNull(req.thumbnailUrl()));
        applyProduct(p, req);
        productRepository.flush();
        return ProductResponse.forManager(p);
    }

    @Transactional
    public ProductResponse changeActive(Long id, boolean active) {
        Product p = findProduct(id);
        p.setActive(active);
        productRepository.flush();
        return ProductResponse.forManager(p);
    }

    @Transactional
    public ProductResponse importStock(Long id, int quantity, String note, Integer actorId) {
        findProduct(id);
        ledger.importStock(id, quantity, blankToNull(note), actorId);
        return ProductResponse.forManager(findProduct(id));
    }

    @Transactional(readOnly = true)
    public PageResult<InventoryTransactionResponse> transactions(Long productId, int page, int size) {
        findProduct(productId);
        var result = transactionRepository.findByProductIdOrderByCreatedAtDescIdDesc(productId,
                PageRequest.of(Math.max(page, 0), clampSize(size)));
        return PageResult.of(result, InventoryTransactionResponse::of);
    }

    private void applyProduct(Product p, ProductRequest req) {
        if (req.categoryId() == null) {
            p.setCategory(null);
        } else {
            p.setCategory(categoryRepository.findById(req.categoryId())
                    .orElseThrow(() -> SupplyException.notFound("Danh mục")));
        }
        p.setDescription(blankToNull(req.description()));
        if (req.lowStockThreshold() != null) {
            p.setLowStockThreshold(req.lowStockThreshold());
        }
        if (req.active() != null) {
            p.setActive(req.active());
        }
    }

    private Product findProduct(Long id) {
        return productRepository.findById(id).orElseThrow(() -> SupplyException.notFound("Học cụ"));
    }

    private static Pageable pageable(int page, int size, String sort) {
        Sort s = switch (sort == null ? "" : sort) {
            case "price_asc" -> Sort.by("price").ascending();
            case "price_desc" -> Sort.by("price").descending();
            case "rating" -> Sort.by(Sort.Order.desc("ratingAvg"), Sort.Order.desc("ratingCount"));
            case "name" -> Sort.by("name").ascending();
            default -> Sort.by(Sort.Order.desc("createdAt"));
        };
        return PageRequest.of(Math.max(page, 0), clampSize(size), s.and(Sort.by("id").descending()));
    }

    private static int clampSize(int size) {
        return Math.min(Math.max(size, 1), 100);
    }

    @SafeVarargs
    private static Specification<Product> and(Specification<Product> first, Specification<Product>... rest) {
        Specification<Product> spec = first;
        for (Specification<Product> s : rest) {
            if (s != null) {
                spec = spec.and(s);
            }
        }
        return spec;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
