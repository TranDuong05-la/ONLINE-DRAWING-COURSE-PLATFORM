package com.artacademy.instructor;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

/** Giảng viên được ADMIN phân công dạy một khóa học (bảng course_instructors, V17). */
@Entity
@Table(name = "course_instructors",
        uniqueConstraints = @UniqueConstraint(name = "uk_course_instructors_course_user",
                columnNames = { "course_id", "user_id" }),
        indexes = { @Index(name = "ix_course_instructors_user", columnList = "user_id"),
                @Index(name = "ix_course_instructors_assigned_by", columnList = "assigned_by_id") })
@Getter
@Setter
@NoArgsConstructor
public class CourseInstructor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Integer id;

    @Column(name = "course_id", nullable = false)
    private Integer courseId;

    @Column(name = "user_id", nullable = false)
    private Integer userId;

    @Column(name = "assigned_by_id", nullable = false)
    private Integer assignedById;

    @CreationTimestamp
    @Column(name = "assigned_at", nullable = false, updatable = false)
    private LocalDateTime assignedAt;

    public CourseInstructor(Integer courseId, Integer userId, Integer assignedById) {
        this.courseId = courseId;
        this.userId = userId;
        this.assignedById = assignedById;
    }
}
