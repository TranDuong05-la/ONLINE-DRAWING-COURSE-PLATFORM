package com.artacademy.learning.notification;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.artacademy.entity.User;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * Một thông báo trong app gửi tới một người dùng (chuông ở trang chủ).
 * {@code eventKey} unique theo người dùng để cùng một sự kiện không tạo thông báo trùng (kể cả khi gọi lại).
 */
@Entity
@Table(name = "learning_notifications",
		uniqueConstraints = @UniqueConstraint(name = "uk_learning_notifications_user_event",
				columnNames = { "user_id", "event_key" }),
		indexes = @Index(name = "ix_learning_notifications_user_created", columnList = "user_id, created_at"))
@Getter
@Setter
public class LearningNotification {

	public enum Type {
		/** Được cấp quyền học một khóa (sau thanh toán / cấp tay). */
		COURSE_ENROLLED,
		/** Admin giao bài tập mới trong khóa đang học. */
		NEW_ASSIGNMENT,
		/** Bài học có video mới trong khóa đang học. */
		NEW_LESSON_VIDEO,
		/** Bài nộp được duyệt đạt. */
		GRADED_APPROVED,
		/** Bài nộp bị yêu cầu sửa. */
		GRADED_REVISION,
		/** Được cấp chứng nhận hoàn thành. */
		CERTIFICATE,
		/** New lesson comment for staff/instructors or a reply to a learner comment. */
		NEW_COMMENT,
		COMMENT_REPLY
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Integer userId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_learning_notifications_user"))
	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	private User userRef;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 30)
	private Type type;

	@Column(name = "event_key", nullable = false, length = 120)
	private String eventKey;

	@Column(nullable = false, length = 200)
	private String title;

	@Column(length = 1000)
	private String body;

	/** Nơi app chuyển tới khi bấm thông báo. */
	@Column(name = "course_id")
	private Long courseId;

	@Column(name = "lesson_id")
	private Long lessonId;

	/** Id chứng nhận / bài nộp / đề bài tùy loại. */
	@Column(name = "target_id")
	private Long targetId;

	@Column(name = "created_at", nullable = false)
	private LocalDateTime createdAt = LocalDateTime.now();

	@Column(name = "read_at")
	private LocalDateTime readAt;
}
