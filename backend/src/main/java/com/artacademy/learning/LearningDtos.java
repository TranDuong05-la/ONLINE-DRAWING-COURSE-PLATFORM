package com.artacademy.learning;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Dữ liệu vào/ra của API học tập (phần C). Mọi response được bọc trong ApiResponse của nhóm. */
public final class LearningDtos {
	private LearningDtos() {
	}

	// ---------------------------------------------------------------- học viên

	public record MyCourseDto(Integer courseId, String title, String thumbnailUrl, int totalLessons,
			int completedLessons, int progressPercent, boolean completed, Integer continueLessonId, Long certificateId,
			LocalDateTime enrolledAt) {
	}

	public record LessonItemDto(Integer id, String title, int durationSeconds, boolean required, boolean completed,
			int lastPositionSeconds, boolean hasAssignment, boolean assignmentRequired, String assignmentStatus) {
	}

	public record ModuleDto(Integer id, String title, List<LessonItemDto> lessons) {
	}

	public record CourseDetailDto(Integer courseId, String title, String thumbnailUrl, String description,
			int totalLessons, int completedLessons, int requiredLessons, int completedRequiredLessons,
			int requiredAssignments, int approvedAssignments, int progressPercent, boolean completed,
			Integer continueLessonId, Long certificateId, List<ModuleDto> modules) {
	}

	public record LessonContentDto(Integer id, Integer courseId, String title, String description, String videoUrl,
			int durationSeconds, String materialUrl, boolean required, int lastPositionSeconds, int watchedSeconds,
			boolean completed, boolean hasAssignment, Integer previousLessonId, Integer nextLessonId) {
	}

	public record ProgressRequest(@NotNull @Min(0) Integer positionSeconds,
			@NotNull @Min(0) @Max(300) Integer watchedDeltaSeconds) {
	}

	public record ProgressResponse(Integer lessonId, int lastPositionSeconds, int watchedSeconds, boolean completed,
			int courseProgressPercent, boolean courseCompleted) {
	}

	public record SubmissionDto(Long id, Long assignmentId, Integer lessonId, int attemptNo, String status, String note,
			BigDecimal score, String feedback, LocalDateTime submittedAt, LocalDateTime reviewedAt, String imagePath) {
	}

	public record AssignmentDto(Long id, Integer lessonId, String lessonTitle, Integer courseId, String title,
			String instructions, boolean required, boolean canSubmit, String latestStatus,
			List<SubmissionDto> submissions) {
	}

	/** Một dòng trong tab "Bài tập" của học viên. */
	public record MyAssignmentDto(Long assignmentId, Integer lessonId, String lessonTitle, Integer courseId,
			String courseTitle, String title, boolean required, String status, BigDecimal score, String feedback,
			LocalDateTime submittedAt) {
	}

	public record CertificateDto(Long id, String code, Integer courseId, String courseTitle, String learnerName,
			LocalDateTime issuedAt, String emailStatus, int emailAttempts, LocalDateTime emailSentAt,
			String emailError, boolean canResendEmail) {
	}

	public record LinkDto(String url) {
	}

	/** Thông báo trong app. App dùng type + courseId / lessonId / targetId để mở đúng màn hình khi bấm. */
	public record NotificationDto(Long id, String type, String title, String body, LocalDateTime createdAt,
			boolean read, Integer courseId, Integer lessonId, Long targetId) {
	}

	public record UnreadCountDto(long unread) {
	}

	/** Admin: chứng nhận đã cấp cho học viên. */
	public record AdminCertificateDto(Long id, String code, Integer courseId, String courseTitle, String learnerName,
			String learnerEmail, LocalDateTime issuedAt, String emailStatus, int emailAttempts,
			LocalDateTime emailSentAt, String emailError) {
	}

	// ---------------------------------------------------------------- admin

	public record AdminSubmissionDto(Long id, Long assignmentId, String assignmentTitle,
			String assignmentInstructions, Integer lessonId, String lessonTitle, Integer courseId, String courseTitle,
			Integer learnerProfileId, String learnerName, String learnerEmail, int attemptNo, String status,
			String note, BigDecimal score, String feedback, LocalDateTime submittedAt, LocalDateTime reviewedAt,
			String imagePath, List<SubmissionDto> history) {
	}

	@io.swagger.v3.oas.annotations.media.Schema(name = "SubmissionReviewRequest")
	public record ReviewRequest(@NotBlank String decision, @Size(max = 2000) String feedback,
			@DecimalMin("0") @DecimalMax("100") BigDecimal score) {
	}

	public record SubmissionSummary(long pending, long needsRevision, long approved) {
	}

	public record CourseOption(Integer id, String title) {
	}

	public record PageDto<T>(List<T> items, long total, int page, int size) {
	}

	/** Quyền học của một học viên trong khóa (màn admin). source: ORDER / MANUAL; status: ACTIVE / REVOKED. */
	public record AdminEnrollmentDto(Long id, Integer userId, String learnerName, String learnerEmail, String status,
			String source, LocalDateTime enrolledAt, boolean completed, LocalDateTime revokedAt, String revokeReason) {
	}

	public record GrantEnrollmentRequest(
			@NotBlank(message = "Nhập email học viên") @jakarta.validation.constraints.Email(message = "Email không hợp lệ") String email) {
	}

	public record RevokeEnrollmentRequest(@Size(max = 120, message = "Lý do tối đa 120 ký tự") String reason) {
	}

	/** Admin soạn đề bài tập cho một bài học. */
	public record AssignmentRequest(@NotBlank @Size(max = 200) String title, @Size(max = 5000) String instructions,
			@NotNull Boolean required, @NotNull Boolean active) {
	}

	public record AdminLessonAssignmentDto(Integer lessonId, String lessonTitle, String moduleTitle, boolean lessonHidden,
			Long assignmentId, String title, String instructions, boolean required, boolean active) {
	}
}
