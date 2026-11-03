package com.artacademy.course.integration;

/** Nội dung một bài học cho C: có link video và tài liệu (khác với API public). */
public record LessonContent(
        Long lessonId,
        Long courseId,
        String title,
        String description,
        String videoUrl,
        String documentUrl,
        int durationSeconds,
        boolean required) {
}
