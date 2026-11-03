package com.artacademy.learning;

import java.math.BigDecimal;
import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.artacademy.entity.User;
import com.artacademy.entity.UserProfile;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * Một lần nộp bài (giữ toàn bộ lịch sử). Lần hiện tại là lần có attempt_no lớn nhất.
 * Chỉ được nộp lần mới khi chưa nộp lần nào hoặc lần gần nhất bị yêu cầu sửa (NEEDS_REVISION).
 */
@Entity
@Table(name = "assignment_submissions",
		uniqueConstraints = @UniqueConstraint(name = "uk_submissions_attempt",
				columnNames = { "assignment_id", "learner_profile_id", "attempt_no" }),
		indexes = { @Index(name = "ix_submissions_status", columnList = "status, submitted_at"),
				@Index(name = "ix_submissions_learner", columnList = "learner_profile_id") })
@Getter
@Setter
public class AssignmentSubmission {

	public enum Status {
		PENDING, APPROVED, NEEDS_REVISION
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "assignment_id", nullable = false)
	private Long assignmentId;

	/** Lưu sẵn để lọc theo khóa / bài mà không cần join. */
	@Column(name = "lesson_id", nullable = false)
	private Integer lessonId;

	@Column(name = "course_id", nullable = false)
	private Integer courseId;

	@Column(name = "learner_profile_id", nullable = false)
	private Integer learnerProfileId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "assignment_id", insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_submissions_assignment"))
	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	private Assignment assignmentRef;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "learner_profile_id", insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_submissions_learner"))
	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	private UserProfile learnerRef;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "reviewed_by_user_id", insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_submissions_reviewer"))
	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	private User reviewerRef;

	/** Khóa lưu ảnh trong kho riêng tư (chỉ tải qua API có kiểm tra quyền). */
	@Column(name = "image_key", nullable = false, length = 255)
	private String imageKey;

	@Column(columnDefinition = "TEXT")
	private String note;

	@Column(name = "attempt_no", nullable = false)
	private Integer attemptNo = 1;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20)
	private Status status = Status.PENDING;

	@Column(precision = 5, scale = 2)
	private BigDecimal score;

	@Column(columnDefinition = "TEXT")
	private String feedback;

	@Column(name = "reviewed_by_user_id")
	private Integer reviewedByUserId;

	@Column(name = "submitted_at", nullable = false)
	private LocalDateTime submittedAt = LocalDateTime.now();

	@Column(name = "reviewed_at")
	private LocalDateTime reviewedAt;

	@Version
	@Column(nullable = false)
	private Integer version;
}
