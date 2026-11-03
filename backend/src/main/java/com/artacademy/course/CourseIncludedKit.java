package com.artacademy.course;

import com.artacademy.product.Product;
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

/** Dụng cụ TẶNG KÈM miễn phí khi mua khóa học (khác "dụng cụ bắt buộc phải mua"). */
@Entity
@Table(name = "course_included_kits",
        uniqueConstraints = @UniqueConstraint(columnNames = {"course_id", "product_id"}))
public class CourseIncludedKit {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private int quantity;

    protected CourseIncludedKit() {
    }

    public CourseIncludedKit(Course course, Product product, int quantity) {
        this.course = course;
        this.product = product;
        this.quantity = quantity;
    }

    public Long getId() {
        return id;
    }

    public Course getCourse() {
        return course;
    }

    public Product getProduct() {
        return product;
    }

    public int getQuantity() {
        return quantity;
    }
}
