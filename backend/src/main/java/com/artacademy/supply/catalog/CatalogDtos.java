package com.artacademy.supply.catalog;

import java.time.LocalDateTime;

import com.artacademy.product.Product;
import com.artacademy.product.ProductCategory;
import com.artacademy.supply.inventory.InventoryTransaction;
import com.artacademy.supply.inventory.InventoryTransactionType;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public final class CatalogDtos {
    private CatalogDtos() {
    }

    // ---------- Danh mục ----------

    public record CategoryResponse(Long id, String name, String description, boolean active, int sortOrder) {
        static CategoryResponse of(ProductCategory c) {
            return new CategoryResponse(c.getId(), c.getName(), c.getDescription(), c.isActive(), c.getSortOrder());
        }
    }

    public record CategoryRequest(
            @NotBlank(message = "Nhập tên danh mục") @Size(max = 100) String name,
            @Size(max = 500) String description,
            Boolean active,
            @Min(0) Integer sortOrder) {
    }

    // ---------- Sản phẩm ----------

    /**
     * Dùng chung cho học viên và quản lý. Các trường kho chi tiết (stockOnHand, stockReserved,
     * lowStockThreshold) chỉ có giá trị khi người xem là STAFF/ADMIN, còn lại là null.
     */
    public record ProductResponse(Long id, String sku, String name, String description, long price,
            String thumbnailUrl, Long categoryId, String categoryName, boolean active, int available,
            boolean inStock, boolean lowStock, double ratingAvg, int ratingCount, Integer stockOnHand,
            Integer stockReserved, Integer lowStockThreshold, LocalDateTime createdAt, LocalDateTime updatedAt) {

        static ProductResponse forShop(Product p) {
            return of(p, false);
        }

        static ProductResponse forManager(Product p) {
            return of(p, true);
        }

        private static ProductResponse of(Product p, boolean manager) {
            ProductCategory c = p.getCategory();
            return new ProductResponse(p.getId(), p.getSku(), p.getName(), p.getDescription(), p.getPrice(),
                    p.getThumbnailUrl(), c == null ? null : c.getId(), c == null ? null : c.getName(), p.isActive(),
                    p.getAvailable(), p.getAvailable() > 0, p.isLowStock(), round(p.getRatingAvg()),
                    p.getRatingCount(), manager ? p.getStockOnHand() : null,
                    manager ? p.getStockReserved() : null, manager ? p.getLowStockThreshold() : null,
                    p.getCreatedAt(), p.getUpdatedAt());
        }
    }

    public record ProductRequest(
            Long categoryId,
            @NotBlank(message = "Nhập SKU")
            @Pattern(regexp = "^[A-Za-z0-9][A-Za-z0-9_-]{1,49}$",
                    message = "SKU 2-50 ký tự, chỉ gồm chữ, số, - và _") String sku,
            @NotBlank(message = "Nhập tên học cụ") @Size(max = 200) String name,
            @Size(max = 4000) String description,
            @NotNull(message = "Nhập giá") @Min(value = 0, message = "Giá không được âm") Long price,
            @Size(max = 500) String thumbnailUrl,
            @Min(0) @Max(100000) Integer lowStockThreshold,
            /** Chỉ dùng khi tạo mới: ghi thành một lần nhập kho đầu tiên. */
            @Min(0) @Max(100000) Integer initialStock,
            Boolean active) {
    }

    public record ProductStatusRequest(@NotNull(message = "Chọn trạng thái") Boolean active) {
    }

    public record StockImportRequest(
            @NotNull(message = "Nhập số lượng") @Min(value = 1, message = "Số lượng phải lớn hơn 0")
            @Max(value = 100000, message = "Tối đa 100000 mỗi lần") Integer quantity,
            @Size(max = 500) String note) {
    }

    public record InventoryTransactionResponse(Long id, InventoryTransactionType type, int quantity,
            Integer orderId, Long shipmentId, boolean gift, int onHandAfter, int reservedAfter, int availableAfter,
            String note, Integer actorUserId, LocalDateTime createdAt) {
        public static InventoryTransactionResponse of(InventoryTransaction t) {
            return new InventoryTransactionResponse(t.getId(), t.getType(), t.getQuantity(), t.getOrderId(),
                    t.getShipmentId(), t.isGift(), t.getOnHandAfter(), t.getReservedAfter(),
                    Math.max(0, t.getOnHandAfter() - t.getReservedAfter()), t.getNote(), t.getActorUserId(),
                    t.getCreatedAt());
        }
    }

    static double round(double v) {
        return Math.round(v * 10) / 10.0;
    }
}
