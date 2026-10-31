package com.artacademy.course.integration;

import java.util.List;

/** Cấu trúc khóa học cho C (chỉ module/bài đang hiển thị, đúng thứ tự). Không chứa link video/tài liệu. */
public record CourseStructure(Integer courseId, String title, List<ModuleItem> modules) {

    public record ModuleItem(Integer id, String title, int sortOrder, List<LessonItem> lessons) {
    }

    /** required = bài bắt buộc (C dùng để xét hoàn thành khóa). */
    public record LessonItem(Integer id, String title, int durationSeconds, boolean required, boolean preview) {
    }
}
