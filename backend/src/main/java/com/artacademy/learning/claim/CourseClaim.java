package com.artacademy.learning.claim;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.artacademy.course.Course;
import com.artacademy.entity.User;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * "Giữ chỗ" một khóa cho một Order của D, trước và sau khi thanh toán.
 *
 * <p>Vòng đời (đã thống nhất với D):
 * RESERVED → CONFIRMED (thanh toán thành công, cấp quyền học),
 * RESERVED → RELEASED (Order hủy / hết hạn / thanh toán thất bại hẳn),
 * CONFIRMED → REFUNDED (hoàn tiền đã được xác nhận, thu hồi quyền học do chính Order đó cấp).
 *
 * <p>{@code activeKey} = "learnerProfileId:courseId" khi claim đang RESERVED/CONFIRMED, null khi đã kết thúc.
 * Cột này UNIQUE nên hai Order không thể cùng giữ một khóa cho cùng một học viên, kể cả khi chạy song song
 * (MySQL không có unique index có điều kiện nên dùng cột null-able này thay thế).
 */
@Entity
@Table(name = "course_claims",
		uniqueConstraints = { @UniqueConstraint(name = "uk_course_claims_order_course", columnNames = { "order_id", "course_id" }),
				@UniqueConstraint(name = "uk_course_claims_active_key", columnNames = "active_key") },
		indexes = { @Index(name = "ix_course_claims_order_item", columnList = "order_item_id"),
				@Index(name = "ix_course_claims_learner_course", columnList = "learner_profile_id, course_id") })
@Getter
@Setter
public class CourseClaim {

	public enum Status {
		RESERVED, CONFIRMED, RELEASED, REFUNDED
	}

	public enum SourceType {
		STANDALONE_COURSE, COMBO_COMPONENT
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "user_id", nullable = false)
	private Integer userId;

	@Column(name = "learner_profile_id", nullable = false)
	private Integer learnerProfileId;

	@Column(name = "course_id", nullable = false)
	private Long courseId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "user_id", insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_course_claims_user"))
	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	private User userRef;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "course_id", insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_course_claims_course"))
	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	private Course courseRef;

	/** Order / OrderItem của D (không đặt khóa ngoại để hai phần độc lập về schema). */
	@Column(name = "order_id", nullable = false)
	private Integer orderId;

	@Column(name = "order_item_id")
	private Integer orderItemId;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "source_type", nullable = false, length = 30)
	private SourceType sourceType = SourceType.STANDALONE_COURSE;

	@Column(name = "component_position")
	private Integer componentPosition;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(nullable = false, length = 20)
	private Status status = Status.RESERVED;

	@Column(name = "active_key", length = 64)
	private String activeKey;

	@Column(name = "reserved_at", nullable = false)
	private LocalDateTime reservedAt;

	@Column(name = "expires_at", nullable = false)
	private LocalDateTime expiresAt;

	@Column(name = "confirmed_at")
	private LocalDateTime confirmedAt;

	@Column(name = "released_at")
	private LocalDateTime releasedAt;

	@Column(name = "release_reason", length = 50)
	private String releaseReason;

	@Column(name = "refunded_at")
	private LocalDateTime refundedAt;

	@Column(name = "refund_operation_id", length = 128)
	private String refundOperationId;

	/** Enrollment được tạo/kích hoạt khi confirm. */
	@Column(name = "enrollment_id")
	private Long enrollmentId;

	@Version
	@Column(nullable = false)
	private Integer version;

	public static String activeKey(Integer learnerProfileId, Long courseId) {
		return learnerProfileId + ":" + courseId;
	}
}
