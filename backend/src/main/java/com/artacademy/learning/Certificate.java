package com.artacademy.learning;

import java.time.LocalDateTime;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.artacademy.course.Course;
import com.artacademy.entity.UserProfile;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;

/**
 * Chứng nhận hoàn thành do hệ thống cấp (không phải chứng chỉ được công nhận bên ngoài).
 * Mỗi học viên một chứng nhận cho mỗi khóa; unique constraint là hàng rào cuối cùng chống cấp trùng.
 * Chứng nhận được LƯU trước, email gửi sau khi commit; email lỗi chỉ đổi email_status.
 */
@Entity
@Table(name = "certificates", uniqueConstraints = {
		@UniqueConstraint(name = "uk_certificates_learner_course", columnNames = { "learner_profile_id", "course_id" }),
		@UniqueConstraint(name = "uk_certificates_code", columnNames = "certificate_code") })
@Getter
@Setter
public class Certificate {

	public enum EmailStatus {
		PENDING, SENT, FAILED
	}

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@Column(name = "certificate_code", nullable = false, length = 50)
	private String certificateCode;

	@Column(name = "learner_profile_id", nullable = false)
	private Integer learnerProfileId;

	@Column(name = "course_id", nullable = false)
	private Integer courseId;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "learner_profile_id", insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_certificates_profile"))
	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	private UserProfile profileRef;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "course_id", insertable = false, updatable = false,
			foreignKey = @ForeignKey(name = "fk_certificates_course"))
	@Getter(AccessLevel.NONE)
	@Setter(AccessLevel.NONE)
	private Course courseRef;

	/** Tên học viên và tên khóa tại thời điểm cấp (đổi tên sau này không làm đổi chứng nhận). */
	@Column(name = "learner_name", nullable = false, length = 100)
	private String learnerName;

	@Column(name = "course_title", nullable = false, length = 200)
	private String courseTitle;

	@Column(name = "pdf_key", nullable = false, length = 255)
	private String pdfKey;

	@Column(name = "signature_hash", nullable = false, length = 64)
	private String signatureHash;

	@Column(name = "issued_at", nullable = false)
	private LocalDateTime issuedAt;

	@Enumerated(EnumType.STRING)
	@JdbcTypeCode(SqlTypes.VARCHAR)
	@Column(name = "email_status", nullable = false, length = 20)
	private EmailStatus emailStatus = EmailStatus.PENDING;

	@Column(name = "email_attempts", nullable = false)
	private Integer emailAttempts = 0;

	@Column(name = "last_email_attempt_at")
	private LocalDateTime lastEmailAttemptAt;

	@Column(name = "email_sent_at")
	private LocalDateTime emailSentAt;

	@Column(name = "email_error", length = 255)
	private String emailError;
}
