package com.artacademy.learning;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.artacademy.course.Course;
import com.artacademy.entity.User;
import com.artacademy.entity.UserProfile;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * Quyền học một khóa. Mỗi hồ sơ học viên chỉ có một dòng cho mỗi khóa (unique), kể cả khi quyền đã bị thu hồi:
 * mua lại thì kích hoạt lại đúng dòng cũ.
 *
 * <p>Các field kết thúc bằng "Ref" là quan hệ CHỈ ĐỌC, chỉ để Hibernate sinh khóa ngoại khi chạy H2 (create-drop).
 * Trên MySQL, bảng và khóa ngoại do Flyway tạo (V12__learning.sql).
 */
@Entity
@Table(name = "user_course_enrollments",
		uniqueConstraints = @UniqueConstraint(name = "uk_enrollments_learner_course",
				columnNames = { "learner_profile_id", "course_id" }),
		indexes = { @Index(name = "ix_enrollments_user", columnList = "user_id"),
				@Index(name = "ix_enrollments_course", columnList = "course_id"),
				@Index(name = "ix_enrollments_order_item", columnList = "source_order_item_id") })
@Getter
@Setter
public class Enrollment {

	public enum Status {
		ACTIVE, REVOKED
	}

	/** Nguồn cấp quyền: ORDER = sau thanh toán (D); MANUAL = admin/dữ liệu mẫu. */
	public enum Source {
		ORDER, MANUAL
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Integer userId;

	@Column(name = "learner_profile_id", nullable = false)
	private Integer learnerProfileId;

	@Column(name = "course_id", nullable = false)
	private Integer courseId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_enrollments_user"))
	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	private User userRef;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "learner_profile_id", insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_enrollments_profile"))
	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	private UserProfile profileRef;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "course_id", insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_enrollments_course"))
	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	private Course courseRef;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20)
	private Status status = Status.ACTIVE;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "source_type", nullable = false, length = 20)
	private Source sourceType = Source.ORDER;

	/** Order / OrderItem của D đã cấp quyền (null khi cấp tay). Refund chỉ thu hồi quyền do chính item đó cấp. */
	@Column(name = "source_order_id")
	private Integer sourceOrderId;

	@Column(name = "source_order_item_id")
	private Integer sourceOrderItemId;

	@Column(name = "completed_lessons_count", nullable = false)
	private Integer completedLessonsCount = 0;

	@Column(name = "is_completed", nullable = false)
	private Boolean completed = false;

	@Column(name = "enrolled_at", nullable = false)
	private LocalDateTime enrolledAt = LocalDateTime.now();

	@Column(name = "completed_at")
	private LocalDateTime completedAt;

	@Column(name = "revoked_at")
	private LocalDateTime revokedAt;

	@Column(name = "revoke_reason", length = 150)
	private String revokeReason;

	@Version
	@Column(nullable = false)
	private Integer version;

	public boolean isActive() {
		return status == Status.ACTIVE;
	}
}
