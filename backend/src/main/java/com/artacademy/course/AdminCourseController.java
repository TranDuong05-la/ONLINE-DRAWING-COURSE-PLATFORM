package com.artacademy.course;

import com.artacademy.common.PageResponse;
import com.artacademy.course.dto.AdminCourseDetailDto;
import com.artacademy.course.dto.AdminModuleDto;
import com.artacademy.course.dto.CourseRequest;
import com.artacademy.course.dto.CourseStatusRequest;
import com.artacademy.course.dto.CourseSummaryDto;
import com.artacademy.course.dto.KitDto;
import com.artacademy.course.dto.KitsRequest;
import com.artacademy.course.dto.ModuleRequest;
import com.artacademy.course.dto.ReorderRequest;
import com.artacademy.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * API quản trị khóa học. Toàn bộ /api/admin/** chỉ cho role ADMIN (cấu hình ở SecurityConfig).
 */
@RestController
@RequestMapping("/api/admin/courses")
@Tag(name = "Admin - Courses", description = "Quản trị khóa học, module, quà tặng kèm (chỉ ADMIN)")
public class AdminCourseController {

    private final CourseAdminService courseService;
    private final CourseStructureService structureService;
    private final CourseKitService kitService;

    public AdminCourseController(CourseAdminService courseService, CourseStructureService structureService,
                                 CourseKitService kitService) {
        this.courseService = courseService;
        this.structureService = structureService;
        this.kitService = kitService;
    }

    @GetMapping
    @Operation(summary = "Danh sách khóa học (mọi trạng thái)")
    public ApiResponse<PageResponse<CourseSummaryDto>> list(
            @RequestParam(required = false) CourseStatus status,
            @RequestParam(required = false) String keyword,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ApiResponse.success("OK", courseService.list(status, keyword, page, size));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Tạo khóa học (luôn bắt đầu ở trạng thái DRAFT)")
    public ApiResponse<AdminCourseDetailDto> create(@Valid @RequestBody CourseRequest req) {
        return ApiResponse.success("Đã tạo khóa học", courseService.create(req));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết khóa học cho admin (kèm điều kiện xuất bản còn thiếu)")
    public ApiResponse<AdminCourseDetailDto> get(@PathVariable Long id) {
        return ApiResponse.success("OK", courseService.get(id));
    }

    @PutMapping("/{id}")
    @Operation(summary = "Sửa thông tin khóa học")
    public ApiResponse<AdminCourseDetailDto> update(@PathVariable Long id, @Valid @RequestBody CourseRequest req) {
        return ApiResponse.success("Đã lưu khóa học", courseService.update(id, req));
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Đổi trạng thái khóa học (PUBLISHED / HIDDEN)",
            description = "Xuất bản phải đủ điều kiện; đã từng xuất bản thì không quay về DRAFT.")
    public ApiResponse<AdminCourseDetailDto> changeStatus(@PathVariable Long id,
                                                          @Valid @RequestBody CourseStatusRequest req) {
        return ApiResponse.success("Đã đổi trạng thái", courseService.changeStatus(id, req.status()));
    }

    /** Gửi toàn bộ danh sách quà tặng kèm (thay thế danh sách cũ). */
    @PutMapping("/{id}/kits")
    @Operation(summary = "Thay toàn bộ danh sách dụng cụ tặng kèm")
    public ApiResponse<List<KitDto>> replaceKits(@PathVariable Long id, @Valid @RequestBody KitsRequest req) {
        return ApiResponse.success("Đã lưu quà tặng", kitService.replaceKits(id, req));
    }

    @PostMapping("/{courseId}/modules")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Thêm module vào khóa học")
    public ApiResponse<AdminModuleDto> createModule(@PathVariable Long courseId,
                                                    @Valid @RequestBody ModuleRequest req) {
        return ApiResponse.success("Đã thêm module", structureService.createModule(courseId, req));
    }

    @PutMapping("/{courseId}/modules/reorder")
    @Operation(summary = "Sắp xếp lại module", description = "Gửi đủ tất cả id (kể cả module đang ẩn) theo thứ tự mới.")
    public ApiResponse<List<AdminModuleDto>> reorderModules(@PathVariable Long courseId,
                                                            @Valid @RequestBody ReorderRequest req) {
        return ApiResponse.success("Đã sắp xếp", structureService.reorderModules(courseId, req.ids()));
    }
}
