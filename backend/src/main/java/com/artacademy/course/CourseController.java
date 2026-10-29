package com.artacademy.course;

import com.artacademy.common.PageResponse;
import com.artacademy.course.dto.CourseDetailDto;
import com.artacademy.course.dto.CourseSummaryDto;
import com.artacademy.dto.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** API cho học viên: xem khóa học. Công khai (không cần đăng nhập), cấu hình ở SecurityConfig. */
@RestController
@RequestMapping("/api/courses")
@Tag(name = "Courses", description = "Danh sách và chi tiết khóa học cho học viên (công khai)")
public class CourseController {

    private final CoursePublicService courseService;

    public CourseController(CoursePublicService courseService) {
        this.courseService = courseService;
    }

    /** GET /api/courses?keyword=&level=BASIC|ADVANCED&sort=newest|price_asc|price_desc&page=0&size=10 */
    @GetMapping
    @Operation(summary = "Danh sách khóa học đang bán",
            description = "Tìm theo tên, lọc cấp độ, sắp xếp theo mới nhất hoặc giá thực bán, có phân trang.")
    public ApiResponse<PageResponse<CourseSummaryDto>> list(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String level,
            @RequestParam(defaultValue = "newest") String sort,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {
        return ApiResponse.success("OK", courseService.list(keyword, level, sort, page, size));
    }

    @GetMapping("/{id}")
    @Operation(summary = "Chi tiết khóa học",
            description = "Khóa nháp/ngừng bán trả 404. Không trả link video và tài liệu của bài không học thử.")
    public ApiResponse<CourseDetailDto> detail(@PathVariable Long id) {
        return ApiResponse.success("OK", courseService.getDetail(id));
    }
}
