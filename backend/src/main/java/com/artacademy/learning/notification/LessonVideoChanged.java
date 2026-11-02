package com.artacademy.learning.notification;

/** Bài học vừa được thêm video hoặc đổi video (phát ra sau khi lưu bài học của phần B đã commit). */
public record LessonVideoChanged(Long lessonId) {
}
