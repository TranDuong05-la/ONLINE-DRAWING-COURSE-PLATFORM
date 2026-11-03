package com.artacademy.product;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.DynamicUpdate;
import org.hibernate.annotations.UpdateTimestamp;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * Học cụ. B tạo bản tối giản (sku, name, price, thumbnail_url, active) để chọn quà tặng kèm;
 * E mở rộng thêm danh mục, mô tả, tồn kho và điểm đánh giá. Không đổi tên bảng/cột đã có.
 *
 * active = true: đang bán, hiện trên cửa hàng. false: ẩn, không bán mới
 * (vẫn giữ/xuất kho được nếu là quà của khóa học đã cấu hình trước đó).
 *
 * Tồn kho: stockOnHand là hàng thực tế trong kho, stockReserved là hàng đang giữ cho đơn chưa xuất.
 * Còn bán được = stockOnHand - stockReserved. Hai cột này chỉ đổi bằng câu UPDATE nguyên tử
 * trong SupplyProductRepository (updatable = false), để việc sửa tên/giá không ghi đè số tồn.
 */
@Entity
@Table(name = "products", indexes = @Index(name = "idx_products_category", columnList = "category_id"))
@DynamicUpdate
public class Product {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String sku;

    @Column(nullable = false, length = 200)
    private String name;

    /** Giá bán, VND nguyên. */
    @Column(nullable = false)
    private long price;

    @Column(name = "thumbnail_url", length = 500)
    private String thumbnailUrl;

    @Column(nullable = false)
    private boolean active = true;

    // ---------- Phần E thêm ----------

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "category_id")
    private ProductCategory category;

    @Column(length = 4000)
    private String description;

    @Column(name = "stock_on_hand", nullable = false, updatable = false)
    private int stockOnHand = 0;

    @Column(name = "stock_reserved", nullable = false, updatable = false)
    private int stockReserved = 0;

    @Column(name = "low_stock_threshold", nullable = false)
    private int lowStockThreshold = 5;

    @Column(name = "rating_avg", nullable = false, updatable = false)
    private double ratingAvg = 0;

    @Column(name = "rating_count", nullable = false, updatable = false)
    private int ratingCount = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    protected Product() {
    }

    public Product(String sku, String name, long price, String thumbnailUrl) {
        this.sku = sku;
        this.name = name;
        this.price = price;
        this.thumbnailUrl = thumbnailUrl;
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public void setSku(String sku) {
        this.sku = sku;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public long getPrice() {
        return price;
    }

    public void setPrice(long price) {
        this.price = price;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public void setThumbnailUrl(String thumbnailUrl) {
        this.thumbnailUrl = thumbnailUrl;
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public ProductCategory getCategory() {
        return category;
    }

    public void setCategory(ProductCategory category) {
        this.category = category;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public int getStockOnHand() {
        return stockOnHand;
    }

    public int getStockReserved() {
        return stockReserved;
    }

    /** Số còn bán được. */
    public int getAvailable() {
        return Math.max(0, stockOnHand - stockReserved);
    }

    public int getLowStockThreshold() {
        return lowStockThreshold;
    }

    public void setLowStockThreshold(int lowStockThreshold) {
        this.lowStockThreshold = lowStockThreshold;
    }

    public boolean isLowStock() {
        return getAvailable() <= lowStockThreshold;
    }

    public double getRatingAvg() {
        return ratingAvg;
    }

    public int getRatingCount() {
        return ratingCount;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }
}
