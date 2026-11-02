package com.artacademy.learning;

import java.util.List;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.artacademy.dto.ApiResponse;
import com.artacademy.learning.LearningDtos.AssignmentDto;
import com.artacademy.learning.LearningDtos.CertificateDto;
import com.artacademy.learning.LearningDtos.CourseDetailDto;
import com.artacademy.learning.LearningDtos.LessonContentDto;
import com.artacademy.learning.LearningDtos.LinkDto;
import com.artacademy.learning.LearningDtos.MyAssignmentDto;
import com.artacademy.learning.LearningDtos.MyCourseDto;
import com.artacademy.learning.LearningDtos.NotificationDto;
import com.artacademy.learning.LearningDtos.ProgressRequest;
import com.artacademy.learning.LearningDtos.ProgressResponse;
import com.artacademy.learning.LearningDtos.SubmissionDto;
import com.artacademy.learning.certificate.CertificateEmailService;
import com.artacademy.learning.certificate.CertificateService;
import com.artacademy.learning.notification.LearningNotification;
import com.artacademy.learning.notification.NotificationService;
import com.artacademy.security.AuthenticatedUserIdResolver;

import jakarta.validation.Valid;

/**
 * API của học viên (/api/learning/**, bắt buộc đăng nhập). userId luôn lấy từ token, không nhận từ client.
 * Controller chỉ nhận request và gọi service; mọi quy tắc nằm trong service.
 */
@RestController
@RequestMapping("/api/learning")
public class LearningController {
	private final AuthenticatedUserIdResolver currentUser;
	private final LearningQueryService query;
	private final ProgressService progress;
	private final AssignmentService assignmentService;
	private final CertificateService certificateService;
	private final CertificateEmailService emailService;
	private final NotificationService notificationService;

	public LearningController(AuthenticatedUserIdResolver currentUser, LearningQueryService query,
			ProgressService progress, AssignmentService assignmentService, CertificateService certificateService,
			CertificateEmailService emailService, NotificationService notificationService) {
		this.currentUser = currentUser;
		this.query = query;
		this.progress = progress;
		this.assignmentService = assignmentService;
		this.certificateService = certificateService;
		this.emailService = emailService;
		this.notificationService = notificationService;
	}

	@GetMapping("/courses")
	public ApiResponse<List<MyCourseDto>> myCourses() {
		return ok(query.myCourses(currentUser.requireUserId()));
	}

	@GetMapping("/courses/{courseId}")
	public ApiResponse<CourseDetailDto> course(@PathVariable Long courseId) {
		return ok(query.courseDetail(currentUser.requireUserId(), courseId));
	}

	@GetMapping("/lessons/{lessonId}")
	public ApiResponse<LessonContentDto> lesson(@PathVariable Long lessonId) {
		return ok(query.lesson(currentUser.requireUserId(), lessonId));
	}

	@PutMapping("/lessons/{lessonId}/progress")
	public ApiResponse<ProgressResponse> saveProgress(@PathVariable Long lessonId,
			@Valid @RequestBody ProgressRequest req) {
		return ok(progress.update(currentUser.requireUserId(), lessonId, req.positionSeconds(),
				req.watchedDeltaSeconds()));
	}

	@GetMapping("/lessons/{lessonId}/assignment")
	public ApiResponse<AssignmentDto> assignment(@PathVariable Long lessonId) {
		return ok(assignmentService.get(currentUser.requireUserId(), lessonId));
	}

	@PostMapping(value = "/lessons/{lessonId}/submissions", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ApiResponse<SubmissionDto> submit(@PathVariable Long lessonId, @RequestPart("image") MultipartFile image,
			@RequestParam(value = "note", required = false) String note) {
		return ApiResponse.success("Đã nộp bài", assignmentService.submit(currentUser.requireUserId(), lessonId, image, note));
	}

	@GetMapping("/assignments")
	public ApiResponse<List<MyAssignmentDto>> myAssignments() {
		return ok(assignmentService.myAssignments(currentUser.requireUserId()));
	}

	@GetMapping("/submissions/{id}/image")
	public ResponseEntity<byte[]> submissionImage(@PathVariable Long id) {
		return imageResponse(assignmentService.image(currentUser.requireUserId(), false, id));
	}

	// ---- thông báo (chuông)

	@GetMapping("/notifications")
	public ApiResponse<List<NotificationDto>> notifications() {
		return ok(notificationService.list(currentUser.requireUserId()).stream().map(LearningController::notificationDto)
				.toList());
	}

	@GetMapping("/notifications/unread-count")
	public ApiResponse<LearningDtos.UnreadCountDto> unreadCount() {
		return ok(new LearningDtos.UnreadCountDto(notificationService.unreadCount(currentUser.requireUserId())));
	}

	@PostMapping("/notifications/{id}/read")
	public ApiResponse<NotificationDto> markRead(@PathVariable Long id) {
		return ok(notificationDto(notificationService.markRead(currentUser.requireUserId(), id)));
	}

	@PostMapping("/notifications/read-all")
	public ApiResponse<Integer> markAllRead() {
		return ok(notificationService.markAllRead(currentUser.requireUserId()));
	}

	static NotificationDto notificationDto(LearningNotification n) {
		return new NotificationDto(n.getId(), n.getType().name(), n.getTitle(), n.getBody(), n.getCreatedAt(),
				n.getReadAt() != null, n.getCourseId(), n.getLessonId(), n.getTargetId());
	}

	@GetMapping("/certificates")
	public ApiResponse<List<CertificateDto>> certificates() {
		return ok(certificateService.listMine(currentUser.requireUserId()).stream().map(this::toDto).toList());
	}

	@GetMapping("/certificates/{id}/pdf")
	public ResponseEntity<byte[]> pdf(@PathVariable Long id) {
		Certificate c = certificateService.getOwned(currentUser.requireUserId(), id);
		return pdfResponse(c, certificateService.pdfBytes(c));
	}

	/** Link ký số ngắn hạn để trình xem PDF / trình duyệt mở file mà không cần header đăng nhập. */
	@GetMapping("/certificates/{id}/download-link")
	public ApiResponse<LinkDto> downloadLink(@PathVariable Long id) {
		return ok(new LinkDto(certificateService.downloadLink(currentUser.requireUserId(), id)));
	}

	@PostMapping("/certificates/{id}/resend-email")
	public ApiResponse<CertificateDto> resendEmail(@PathVariable Long id) {
		certificateService.getOwned(currentUser.requireUserId(), id);
		Certificate c = certificateService.reserveEmailAttempt(id, true);
		emailService.deliverAsync(id);
		return ApiResponse.success("Đang gửi lại email", toDto(c));
	}

	static <T> ApiResponse<T> ok(T data) {
		return ApiResponse.success("OK", data);
	}

	static ResponseEntity<byte[]> pdfResponse(Certificate c, byte[] bytes) {
		return ResponseEntity.ok().contentType(MediaType.APPLICATION_PDF)
				.header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + c.getCertificateCode() + ".pdf\"")
				.cacheControl(CacheControl.noStore()).body(bytes);
	}

	static ResponseEntity<byte[]> imageResponse(byte[] b) {
		MediaType type = b.length > 3 && (b[0] & 0xFF) == 0x89 ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG;
		return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(type).body(b);
	}

	private CertificateDto toDto(Certificate c) {
		return new CertificateDto(c.getId(), c.getCertificateCode(), c.getCourseId(), c.getCourseTitle(),
				c.getLearnerName(), c.getIssuedAt(), c.getEmailStatus().name(), c.getEmailAttempts(),
				c.getEmailSentAt(), c.getEmailError(), certificateService.canResend(c));
	}
}
