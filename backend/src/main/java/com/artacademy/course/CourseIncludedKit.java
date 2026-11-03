package com.artacademy.course;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/**
 * Dụng cụ TẶNG KÈM miễn phí khi mua khóa học (khác "dụng cụ bắt buộc phải mua").
 * Bảng products thuộc E: B chỉ lưu productId (INT), không ánh xạ bảng products và không có khóa ngoại.
 * Thông tin sản phẩm (sku, tên, đang bán không) tra qua {@link com.artacademy.product.ProductCatalog}.
 */
@Entity
@Table(name = "course_included_kits",
        uniqueConstraints = @UniqueConstraint(columnNames = {"course_id", "product_id"}))
public class CourseIncludedKit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(name = "product_id", nullable = false)
    private Integer productId;

    @Column(nullable = false)
    private int quantity;

    protected CourseIncludedKit() {
    }

    public CourseIncludedKit(Course course, Integer productId, int quantity) {
        this.course = course;
        this.productId = productId;
        this.quantity = quantity;
    }

    public Integer getId() {
        return id;
    }

    public Course getCourse() {
        return course;
    }

    public Integer getProductId() {
        return productId;
    }

    public int getQuantity() {
        return quantity;
    }
}
