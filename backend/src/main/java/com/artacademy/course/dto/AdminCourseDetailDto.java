package com.artacademy.course.dto;

import java.util.List;

/**
 * Chi tiết khóa ở màn admin (mọi trạng thái, kể cả module/bài đang ẩn).
 * publishProblems: các điều kiện còn thiếu để xuất bản (rỗng = có thể xuất bản). App dùng để báo cho admin.
 */
public record AdminCourseDetailDto(
        CourseSummaryDto course,
        String description,
        List<AdminModuleDto> modules,
        List<KitDto> kits,
        List<String> publishProblems) {
}
