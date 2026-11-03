package com.artacademy.learning;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.artacademy.dto.ApiResponse;
import com.artacademy.learning.LearningDtos.AdminCertificateDto;
import com.artacademy.learning.LearningDtos.AdminLessonAssignmentDto;
import com.artacademy.learning.LearningDtos.AdminSubmissionDto;
import com.artacademy.learning.LearningDtos.AssignmentRequest;
import com.artacademy.learning.LearningDtos.CourseOption;
import com.artacademy.learning.LearningDtos.PageDto;
import com.artacademy.learning.LearningDtos.ReviewRequest;
import com.artacademy.learning.LearningDtos.SubmissionSummary;
import com.artacademy.learning.certificate.CertificateService;
import com.artacademy.security.AuthenticatedUserIdResolver;

import jakarta.validation.Valid;

/**
 * Admin: soạn đề bài tập và chấm bài. Quyền ADMIN được chặn ở SecurityConfig ("/api/admin/**"),
 * không chỉ ẩn nút bên Flutter.
 */
@RestController
@RequestMapping("/api/admin")
public class AdminLearningController {
	private final AssignmentService service;
	private final AuthenticatedUserIdResolver currentUser;
	private final CertificateService certificateService;
	private final LearnerProfileService learnerProfiles;
	private final AdminEnrollmentService enrollments;

	public AdminLearningController(AssignmentService service, AuthenticatedUserIdResolver currentUser,
			CertificateService certificateService, LearnerProfileService learnerProfiles,
			AdminEnrollmentService enrollments) {
		this.certificateService = certificateService;
		this.learnerProfiles = learnerProfiles;
		this.service = service;
		this.currentUser = currentUser;
		this.enrollments = enrollments;
	}

	// ---- quyền học cấp tay

	@GetMapping("/learning/courses/{courseId}/enrollments")
	public ApiResponse<List<LearningDtos.AdminEnrollmentDto>> enrollments(@PathVariable Integer courseId) {
		return LearningController.ok(enrollments.list(courseId));
	}

	@PostMapping("/learning/courses/{courseId}/enrollments")
	public ApiResponse<LearningDtos.AdminEnrollmentDto> grant(@PathVariable Integer courseId,
			@Valid @RequestBody LearningDtos.GrantEnrollmentRequest req) {
		return ApiResponse.success("Đã cấp quyền học", enrollments.grant(courseId, req.email()));
	}

	@PostMapping("/learning/enrollments/{id}/revoke")
	public ApiResponse<LearningDtos.AdminEnrollmentDto> revoke(@PathVariable Long id,
			@Valid @RequestBody(required = false) LearningDtos.RevokeEnrollmentRequest req) {
		return ApiResponse.success("Đã thu hồi quyền học", enrollments.revoke(id, req == null ? null : req.reason()));
	}

	// ---- đề bài

	@GetMapping("/learning/courses/{courseId}/assignments")
	public ApiResponse<List<AdminLessonAssignmentDto>> lessonAssignments(@PathVariable Integer courseId) {
		return LearningController.ok(service.lessonsWithAssignments(courseId));
	}

	@PutMapping("/lessons/{lessonId}/assignment")
	public ApiResponse<AdminLessonAssignmentDto> saveAssignment(@PathVariable Integer lessonId,
			@Valid @RequestBody AssignmentRequest req) {
		return ApiResponse.success("Đã lưu đề bài tập", service.upsert(lessonId, req));
	}

	// ---- chứng nhận đã cấp cho học viên

	@GetMapping("/certificates")
	public ApiResponse<PageDto<AdminCertificateDto>> certificates(@RequestParam(required = false) Integer courseId,
			@RequestParam(required = false) String q, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		var result = certificateService.searchAll(courseId, q, page, size);
		return LearningController.ok(new PageDto<>(result.getContent().stream().map(this::toCertificateDto).toList(),
				result.getTotalElements(), result.getNumber(), result.getSize()));
	}

	@GetMapping("/certificates/{id}/download-link")
	public ApiResponse<LearningDtos.LinkDto> certificateLink(@PathVariable Long id) {
		return LearningController.ok(new LearningDtos.LinkDto(certificateService.adminDownloadLink(id)));
	}

	private AdminCertificateDto toCertificateDto(Certificate c) {
		String email = learnerProfiles.emailOf(c.getLearnerProfileId());
		return new AdminCertificateDto(c.getId(), c.getCertificateCode(), c.getCourseId(), c.getCourseTitle(),
				c.getLearnerName(), email, c.getIssuedAt(), c.getEmailStatus().name(), c.getEmailAttempts(),
				c.getEmailSentAt(), c.getEmailError());
	}

	// ---- chấm bài

	@GetMapping("/submissions")
	public ApiResponse<PageDto<AdminSubmissionDto>> list(@RequestParam(required = false) Integer courseId,
			@RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "20") int size) {
		return LearningController.ok(service.list(courseId, status, page, size));
	}

	@GetMapping("/submissions/summary")
	public ApiResponse<SubmissionSummary> summary() {
		return LearningController.ok(service.summary());
	}

	@GetMapping("/submissions/courses")
	public ApiResponse<List<CourseOption>> courses() {
		return LearningController.ok(service.coursesWithSubmissions());
	}

	@GetMapping("/submissions/{id}")
	public ApiResponse<AdminSubmissionDto> detail(@PathVariable Long id) {
		return LearningController.ok(service.detail(id));
	}

	@GetMapping("/submissions/{id}/image")
	public ResponseEntity<byte[]> image(@PathVariable Long id) {
		return LearningController.imageResponse(service.image(currentUser.requireUserId(), true, id));
	}

	@PostMapping("/submissions/{id}/review")
	public ApiResponse<AdminSubmissionDto> review(@PathVariable Long id, @Valid @RequestBody ReviewRequest req) {
		return ApiResponse.success("Đã lưu kết quả chấm", service.review(currentUser.requireUserId(), id, req));
	}
}
