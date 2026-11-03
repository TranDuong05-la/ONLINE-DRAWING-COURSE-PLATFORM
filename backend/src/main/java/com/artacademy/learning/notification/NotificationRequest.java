package com.artacademy.learning.notification;

/**
 * Yêu cầu tạo thông báo, được phát ra (ApplicationEvent) trong transaction của nghiệp vụ và xử lý SAU KHI commit.
 * Gửi cho một người ({@code userId}) hoặc cho mọi học viên đang có quyền học một khóa ({@code fanOutCourseId}).
 */
public record NotificationRequest(LearningNotification.Type type, String eventKey, Integer userId,
		Long fanOutCourseId, String title, String body, Long courseId, Long lessonId, Long targetId) {

	public static NotificationRequest toUser(Integer userId, LearningNotification.Type type, String eventKey,
			String title, String body, Long courseId, Long lessonId, Long targetId) {
		return new NotificationRequest(type, eventKey, userId, null, title, body, courseId, lessonId, targetId);
	}

	public static NotificationRequest toCourseLearners(Long courseId, LearningNotification.Type type, String eventKey,
			String title, String body, Long lessonId, Long targetId) {
		return new NotificationRequest(type, eventKey, null, courseId, title, body, courseId, lessonId, targetId);
	}
}
