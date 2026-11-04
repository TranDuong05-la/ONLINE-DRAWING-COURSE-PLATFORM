package com.artacademy.instructor;

import com.artacademy.common.BadRequestException;
import com.artacademy.course.CourseAdminService;
import com.artacademy.course.CourseStructureService;
import com.artacademy.course.dto.AdminCourseDetailDto;
import com.artacademy.course.dto.AdminLessonDto;
import com.artacademy.course.dto.AdminModuleDto;
import com.artacademy.course.dto.CourseSummaryDto;
import com.artacademy.course.dto.LessonRequest;
import com.artacademy.course.dto.ModuleRequest;
import com.artacademy.course.dto.ReorderRequest;
import com.artacademy.dto.ApiResponse;
import com.artacademy.instructor.InstructorAccess.Caller;
import com.artacademy.learning.AdminEnrollmentService;
import com.artacademy.learning.AssignmentService;
import com.artacademy.learning.LearningController;
import com.artacademy.learning.LearningDtos.AdminEnrollmentDto;
import com.artacademy.learning.LearningDtos.AdminLessonAssignmentDto;
import com.artacademy.learning.LearningDtos.AdminSubmissionDto;
import com.artacademy.learning.LearningDtos.AssignmentRequest;
import com.artacademy.learning.LearningDtos.CourseOption;
import com.artacademy.learning.LearningDtos.PageDto;
import com.artacademy.learning.LearningDtos.ReviewRequest;
import com.artacademy.learning.LearningDtos.SubmissionSummary;
import com.artacademy.media.MediaUrlSigner;
import com.artacademy.upload.FileStorageService;
import com.artacademy.upload.UploadController.UploadResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * Khu vực giảng viên. SecurityConfig cho INSTRUCTOR + ADMIN vào /api/instructor/**; mỗi hàm gọi
 * {@link InstructorAccess} để chỉ làm được trên khóa được phân công. Dùng lại đúng service của admin nên mọi quy tắc
 * (không xóa chỉ ẩn, khóa đang bán phải giữ đủ điều kiện bán, chấm bài khóa dòng...) giữ nguyên.
 *
 * <p>Giảng viên KHÔNG tạo khóa, đổi giá, xuất bản / ẩn khóa, cấp / thu hồi quyền học: việc đó vẫn của ADMIN.
 */
@RestController
@RequestMapping("/api/instructor")
@Tag(name = "Instructor", description = "Giảng viên: nội dung, học viên, chấm bài của khóa được phân công")
public class InstructorController {

    private final InstructorAccess access;
    private final CourseInstructorService instructors;
    private final CourseAdminService courseService;
    private final CourseStructureService structure;
    private final AssignmentService assignments;
    private final AdminEnrollmentService enrollments;
    private final FileStorageService storage;
    private final MediaUrlSigner signer;

    public InstructorController(InstructorAccess access, CourseInstructorService instructors,
                                CourseAdminService courseService, CourseStructureService structure,
                                AssignmentService assignments, AdminEnrollmentService enrollments,
                                FileStorageService storage, MediaUrlSigner signer) {
        this.access = access;
        this.instructors = instructors;
        this.courseService = courseService;
        this.structure = structure;
        this.assignments = assignments;
        this.enrollments = enrollments;
        this.storage = storage;
        this.signer = signer;
    }

    // ---- khóa học được phân công

    @GetMapping("/courses")
    @Operation(summary = "Khóa học được phân công (ADMIN thấy mọi khóa)")
    public ApiResponse<List<CourseSummaryDto>> myCourses() {
        return ApiResponse.success("OK", instructors.myCourses(access.courseIds(access.caller())));
    }

    @GetMapping("/courses/{courseId}")
    @Operation(summary = "Chi tiết khóa (module, bài học, điều kiện xuất bản còn thiếu)")
    public ApiResponse<AdminCourseDetailDto> course(@PathVariable Integer courseId) {
        access.requireCourse(courseId);
        return ApiResponse.success("OK", courseService.get(courseId));
    }

    // ---- nội dung: module, bài học (không xóa, chỉ ẩn)

    @PostMapping("/courses/{courseId}/modules")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminModuleDto> createModule(@PathVariable Integer courseId,
                                                    @Valid @RequestBody ModuleRequest req) {
        access.requireCourse(courseId);
        return ApiResponse.success("Đã thêm module", structure.createModule(courseId, req));
    }

    @PutMapping("/courses/{courseId}/modules/reorder")
    public ApiResponse<List<AdminModuleDto>> reorderModules(@PathVariable Integer courseId,
                                                            @Valid @RequestBody ReorderRequest req) {
        access.requireCourse(courseId);
        return ApiResponse.success("Đã sắp xếp", structure.reorderModules(courseId, req.ids()));
    }

    @PutMapping("/modules/{id}")
    public ApiResponse<AdminModuleDto> updateModule(@PathVariable Integer id, @Valid @RequestBody ModuleRequest req) {
        access.requireModule(id);
        return ApiResponse.success("Đã lưu module", structure.updateModule(id, req));
    }

    @PostMapping("/modules/{moduleId}/lessons")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<AdminLessonDto> createLesson(@PathVariable Integer moduleId,
                                                    @Valid @RequestBody LessonRequest req) {
        access.requireModule(moduleId);
        return ApiResponse.success("Đã thêm bài học", structure.createLesson(moduleId, req));
    }

    @PutMapping("/modules/{moduleId}/lessons/reorder")
    public ApiResponse<List<AdminLessonDto>> reorderLessons(@PathVariable Integer moduleId,
                                                            @Valid @RequestBody ReorderRequest req) {
        access.requireModule(moduleId);
        return ApiResponse.success("Đã sắp xếp", structure.reorderLessons(moduleId, req.ids()));
    }

    @PutMapping("/lessons/{id}")
    public ApiResponse<AdminLessonDto> updateLesson(@PathVariable Integer id, @Valid @RequestBody LessonRequest req) {
        access.requireLesson(id);
        return ApiResponse.success("Đã lưu bài học", structure.updateLesson(id, req));
    }

    @PostMapping("/uploads")
    @Operation(summary = "Upload video / PDF / ảnh cho bài học", description = "Giống /api/admin/uploads.")
    public ApiResponse<UploadResponse> upload(@RequestParam("file") MultipartFile file) {
        access.caller();
        return ApiResponse.success("Đã tải lên", new UploadResponse(storage.store(file)));
    }

    @GetMapping("/media/sign")
    @Operation(summary = "Link có hạn để xem thử video/PDF đã upload")
    public ApiResponse<SignedMedia> sign(@RequestParam String path) {
        access.caller();
        if (path == null || path.isBlank()) {
            throw new BadRequestException("Thiếu đường dẫn file");
        }
        return ApiResponse.success("OK", new SignedMedia(signer.signIfProtected(path.trim()), signer.ttlSeconds()));
    }

    public record SignedMedia(String url, long expiresInSeconds) {
    }

    // ---- đề bài tập

    @GetMapping("/courses/{courseId}/assignments")
    public ApiResponse<List<AdminLessonAssignmentDto>> lessonAssignments(@PathVariable Integer courseId) {
        access.requireCourse(courseId);
        return ApiResponse.success("OK", assignments.lessonsWithAssignments(courseId));
    }

    @PutMapping("/lessons/{lessonId}/assignment")
    public ApiResponse<AdminLessonAssignmentDto> saveAssignment(@PathVariable Integer lessonId,
                                                                @Valid @RequestBody AssignmentRequest req) {
        access.requireLesson(lessonId);
        return ApiResponse.success("Đã lưu đề bài tập", assignments.upsert(lessonId, req));
    }

    // ---- học viên và tiến độ (chỉ xem; cấp / thu hồi quyền học vẫn là của ADMIN)

    @GetMapping("/courses/{courseId}/enrollments")
    public ApiResponse<List<AdminEnrollmentDto>> enrollments(@PathVariable Integer courseId) {
        access.requireCourse(courseId);
        return ApiResponse.success("OK", enrollments.list(courseId));
    }

    // ---- chấm bài

    @GetMapping("/submissions")
    public ApiResponse<PageDto<AdminSubmissionDto>> submissions(@RequestParam(required = false) Integer courseId,
            @RequestParam(required = false) String status, @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        List<Integer> mine = access.courseIds(access.caller());
        return ApiResponse.success("OK", mine == null
                ? assignments.list(courseId, status, page, size)
                : assignments.listInCourses(mine, courseId, status, page, size));
    }

    @GetMapping("/submissions/summary")
    public ApiResponse<SubmissionSummary> summary() {
        List<Integer> mine = access.courseIds(access.caller());
        return ApiResponse.success("OK", mine == null ? assignments.summary() : assignments.summaryInCourses(mine));
    }

    @GetMapping("/submissions/courses")
    public ApiResponse<List<CourseOption>> submissionCourses() {
        List<Integer> mine = access.courseIds(access.caller());
        return ApiResponse.success("OK",
                mine == null ? assignments.coursesWithSubmissions() : assignments.coursesWithSubmissionsIn(mine));
    }

    @GetMapping("/submissions/{id}")
    public ApiResponse<AdminSubmissionDto> submission(@PathVariable Long id) {
        access.requireSubmission(id);
        return ApiResponse.success("OK", assignments.detail(id));
    }

    @GetMapping("/submissions/{id}/image")
    public ResponseEntity<byte[]> image(@PathVariable Long id) {
        Caller c = access.requireSubmission(id);
        return LearningController.imageResponse(assignments.image(c.userId(), true, id));
    }

    @PostMapping("/submissions/{id}/review")
    public ApiResponse<AdminSubmissionDto> review(@PathVariable Long id, @Valid @RequestBody ReviewRequest req) {
        Caller c = access.requireSubmission(id);
        return ApiResponse.success("Đã lưu kết quả chấm", assignments.review(c.userId(), id, req));
    }
}
