package com.artacademy.bundle;

import com.artacademy.course.Course;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.Column;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;

/** Một khóa học nằm trong combo. */
@Entity
@Table(name = "bundle_items", uniqueConstraints = {
        @UniqueConstraint(columnNames = {"bundle_id", "course_id"}),
        @UniqueConstraint(name = "uk_bundle_items_bundle_position", columnNames = {"bundle_id", "position"})})
public class BundleItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "bundle_id", nullable = false)
    private CourseBundle bundle;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "course_id", nullable = false)
    private Course course;

    @Column(name = "position", nullable = false)
    private int position = 1;

    protected BundleItem() {
    }

    public BundleItem(CourseBundle bundle, Course course, int position) {
        this.bundle = bundle;
        this.course = course;
        this.position = position;
    }

    public Long getId() {
        return id;
    }

    public CourseBundle getBundle() {
        return bundle;
    }

    public Course getCourse() {
        return course;
    }

    public int getPosition() {
        return position;
    }
}
