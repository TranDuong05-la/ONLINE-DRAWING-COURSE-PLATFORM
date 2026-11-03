package com.artacademy.course.dto;

/**
 * Bài học ở API public. KHÔNG có videoUrl và link tài liệu (tránh lộ nội dung trả phí).
 * Chỉ bài học thử (preview = true) mới có previewVideoUrl.
 */
public record PublicLessonDto(
        Long id,
        String title,
        String description,
        int durationSeconds,
        boolean preview,
        boolean required,
        boolean hasDocument,
        String previewVideoUrl) {
}
