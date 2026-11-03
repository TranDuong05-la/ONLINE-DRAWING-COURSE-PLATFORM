package com.artacademy.course.dto;

import java.util.List;

/** Chi tiết khóa cho học viên: thông tin + module/bài hiển thị (đúng thứ tự) + quà tặng kèm. */
public record CourseDetailDto(
        CourseSummaryDto course,
        String description,
        List<PublicModuleDto> modules,
        List<KitDto> kits) {
}
