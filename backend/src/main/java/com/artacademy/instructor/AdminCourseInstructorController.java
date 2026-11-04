package com.artacademy.instructor;

import com.artacademy.dto.ApiResponse;
import com.artacademy.instructor.CourseInstructorService.InstructorDto;
import com.artacademy.security.AuthenticatedUserIdResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/** ADMIN phân công giảng viên cho khóa học. Chỉ ADMIN (SecurityConfig: /api/admin/**). */
@RestController
@RequestMapping("/api/admin/courses/{courseId}/instructors")
@Tag(name = "Admin - Course instructors", description = "Phân công giảng viên cho khóa học (chỉ ADMIN)")
public class AdminCourseInstructorController {

    public record AssignInstructorRequest(
            @NotBlank(message = "Nhập email giảng viên") @Email(message = "Email không hợp lệ") String email) {
    }

    private final CourseInstructorService service;
    private final AuthenticatedUserIdResolver currentUser;

    public AdminCourseInstructorController(CourseInstructorService service, AuthenticatedUserIdResolver currentUser) {
        this.service = service;
        this.currentUser = currentUser;
    }

    @GetMapping
    @Operation(summary = "Giảng viên đang được phân công cho khóa")
    public ApiResponse<List<InstructorDto>> list(@PathVariable Integer courseId) {
        return ApiResponse.success("OK", service.list(courseId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Phân công giảng viên (tài khoản phải có role INSTRUCTOR)")
    public ApiResponse<InstructorDto> assign(@PathVariable Integer courseId,
                                             @Valid @RequestBody AssignInstructorRequest req) {
        return ApiResponse.success("Đã phân công giảng viên",
                service.assign(courseId, req.email(), currentUser.requireUserId()));
    }

    @DeleteMapping("/{userId}")
    @Operation(summary = "Gỡ giảng viên khỏi khóa")
    public ApiResponse<Void> unassign(@PathVariable Integer courseId, @PathVariable Integer userId) {
        service.unassign(courseId, userId);
        return ApiResponse.success("Đã gỡ giảng viên", null);
    }
}
