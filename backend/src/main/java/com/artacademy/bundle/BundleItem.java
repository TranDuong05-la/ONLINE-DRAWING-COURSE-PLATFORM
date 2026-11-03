package com.artacademy.bundle;

import com.artacademy.course.Course;
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

/** Một khóa học nằm trong combo, kèm vị trí (1, 2, 3...) theo đúng thứ tự admin chọn. */
@Entity
@Table(name = "bundle_items", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"bundle_id", "course_id"}),
        @UniqueConstraint(columnNames = {"bundle_id", "position"})})
public class BundleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bundle_id", nullable = false)
    private CourseBundle bundle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    /** Vị trí trong combo, bắt đầu từ 1, không trùng trong cùng combo. */
    @Column(name = "position", nullable = false)
    private int position;

    protected BundleItem() {
    }

    public BundleItem(CourseBundle bundle, Course course, int position) {
        this.bundle = bundle;
        this.course = course;
        this.position = position;
    }

    public Integer getId() {
        return id;
    }

    public int getPosition() {
        return position;
    }

    public CourseBundle getBundle() {
        return bundle;
    }

    public Course getCourse() {
        return course;
    }
}
