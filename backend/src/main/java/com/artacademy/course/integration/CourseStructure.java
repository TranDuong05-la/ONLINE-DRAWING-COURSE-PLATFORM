package com.artacademy.course.integration;

import java.util.List;

/** Cấu trúc khóa học cho C (chỉ module/bài đang hiển thị, đúng thứ tự). Không chứa link video/tài liệu. */
public record CourseStructure(Long courseId, String title, List<ModuleItem> modules) {

    public record ModuleItem(Long id, String title, int sortOrder, List<LessonItem> lessons) {
    }

    /** required = bài bắt buộc (C dùng để xét hoàn thành khóa). */
    public record LessonItem(Long id, String title, int durationSeconds, boolean required, boolean preview) {
    }
}
