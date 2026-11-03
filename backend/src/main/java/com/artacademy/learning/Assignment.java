package com.artacademy.learning;

import java.time.LocalDateTime;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.artacademy.course.CourseLesson;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/** Đề bài tập của một bài học (tối đa một đề mỗi bài). Admin soạn, học viên nộp ảnh bài làm. */
@Entity
@Table(name = "assignments",
		uniqueConstraints = @UniqueConstraint(name = "uk_assignments_lesson", columnNames = "lesson_id"))
@Getter
@Setter
public class Assignment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "lesson_id", nullable = false)
	private Integer lessonId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "lesson_id", insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_assignments_lesson"))
	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	private CourseLesson lessonRef;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(columnDefinition = "TEXT")
	private String instructions;

	/** Bài tập bắt buộc: phải được duyệt (APPROVED) thì khóa mới hoàn thành. */
	@Column(name = "is_required", nullable = false)
	private Boolean required = true;

	/** Ẩn đề (không xóa, để giữ lịch sử bài nộp). Đề ẩn không tính vào điều kiện hoàn thành. */
	@Column(name = "is_active", nullable = false)
	private Boolean active = true;

	@CreationTimestamp
	@Column(name = "created_at", nullable = false, updatable = false)
	private LocalDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at")
	private LocalDateTime updatedAt;
}
