package com.artacademy.learning;

import java.time.LocalDateTime;

import com.artacademy.course.CourseLesson;
import com.artacademy.entity.UserProfile;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * Tiến độ một bài học của một học viên. {@code watchedSeconds} là số giây đã thực sự phát (server tự giới hạn),
 * {@code lastPositionSeconds} chỉ là vị trí để xem tiếp.
 */
@Entity
@Table(name = "user_lesson_progress",
		uniqueConstraints = @UniqueConstraint(name = "uk_lesson_progress_learner_lesson",
				columnNames = { "learner_profile_id", "lesson_id" }),
		indexes = @Index(name = "ix_lesson_progress_lesson", columnList = "lesson_id"))
@Getter
@Setter
public class LessonProgress {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "learner_profile_id", nullable = false)
	private Integer learnerProfileId;

	@Column(name = "lesson_id", nullable = false)
	private Integer lessonId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "learner_profile_id", insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_lesson_progress_profile"))
	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	private UserProfile profileRef;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "lesson_id", insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_lesson_progress_lesson"))
	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	private CourseLesson lessonRef;

	@Column(name = "is_completed", nullable = false)
	private Boolean completed = false;

	@Column(name = "watched_seconds", nullable = false)
	private Integer watchedSeconds = 0;

	@Column(name = "last_position_seconds", nullable = false)
	private Integer lastPositionSeconds = 0;

	@Column(name = "last_watched_at")
	private LocalDateTime lastWatchedAt;

	@Column(name = "completed_at")
	private LocalDateTime completedAt;
}
