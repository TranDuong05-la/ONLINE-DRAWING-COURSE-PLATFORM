package com.artacademy.course;

import com.artacademy.course.dto.AdminLessonDto;
import com.artacademy.course.dto.AdminModuleDto;
import com.artacademy.course.dto.LessonRequest;
import com.artacademy.course.dto.ModuleRequest;
import com.artacademy.course.dto.ReorderRequest;
import com.artacademy.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * API quản trị module và bài học (không có DELETE: chỉ ẩn bằng "hidden": true). Chỉ ADMIN.
 */
@RestController
@RequestMapping("/api/admin")
@Tag(name = "Admin - Courses", description = "Quản trị khóa học, module, quà tặng kèm (chỉ ADMIN)")
public class AdminStructureController {

    private final CourseStructureService structureService;

    public AdminStructureController(CourseStructureService structureService) {
        this.structureService = structureService;
    }

    @PutMapping("/modules/{id}")
    @Operation(summary = "Sửa module (tên, ẩn/hiện)")
    public ApiResponse<AdminModuleDto> updateModule(@PathVariable Long id, @Valid @RequestBody ModuleRequest req) {
        return ApiResponse.success("Đã lưu module", structureService.updateModule(id, req));
    }

    @PostMapping("/modules/{moduleId}/lessons")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Thêm bài học vào module")
    public ApiResponse<AdminLessonDto> createLesson(@PathVariable Long moduleId,
                                                    @Valid @RequestBody LessonRequest req) {
        return ApiResponse.success("Đã thêm bài học", structureService.createLesson(moduleId, req));
    }

    @PutMapping("/modules/{moduleId}/lessons/reorder")
    @Operation(summary = "Sắp xếp lại bài học trong module")
    public ApiResponse<List<AdminLessonDto>> reorderLessons(@PathVariable Long moduleId,
                                                            @Valid @RequestBody ReorderRequest req) {
        return ApiResponse.success("Đã sắp xếp", structureService.reorderLessons(moduleId, req.ids()));
    }

    @PutMapping("/lessons/{id}")
    @Operation(summary = "Sửa bài học (PUT thay thế toàn bộ các trường)")
    public ApiResponse<AdminLessonDto> updateLesson(@PathVariable Long id, @Valid @RequestBody LessonRequest req) {
        return ApiResponse.success("Đã lưu bài học", structureService.updateLesson(id, req));
    }
}
