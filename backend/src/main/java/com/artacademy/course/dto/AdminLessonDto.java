package com.artacademy.course.dto;

/** Bài học ở màn admin: đầy đủ mọi trường, kể cả đang ẩn và link video/tài liệu. */
public record AdminLessonDto(
        Integer id,
        Integer moduleId,
        String title,
        String description,
        String videoUrl,
        int durationSeconds,
        String materialUrl,
        int sortOrder,
        boolean preview,
        boolean required,
        boolean hidden) {
}
