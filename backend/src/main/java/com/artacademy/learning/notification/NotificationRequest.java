package com.artacademy.learning.notification;

import java.util.List;

/**
 * Yêu cầu tạo thông báo, được phát ra (ApplicationEvent) trong transaction của nghiệp vụ và xử lý SAU KHI commit.
 * Gửi cho một người ({@code userId}), cho một danh sách người ({@code userIds}) hoặc cho mọi học viên đang có
 * quyền học một khóa ({@code fanOutCourseId}).
 */
public record NotificationRequest(LearningNotification.Type type, String eventKey, Integer userId,
		List<Integer> userIds, Integer fanOutCourseId, String title, String body, Integer courseId, Integer lessonId,
		Long targetId) {

	public static NotificationRequest toUser(Integer userId, LearningNotification.Type type, String eventKey,
			String title, String body, Integer courseId, Integer lessonId, Long targetId) {
		return new NotificationRequest(type, eventKey, userId, null, null, title, body, courseId, lessonId, targetId);
	}

	public static NotificationRequest toUsers(List<Integer> userIds, LearningNotification.Type type, String eventKey,
			String title, String body, Integer courseId, Integer lessonId, Long targetId) {
		return new NotificationRequest(type, eventKey, null, List.copyOf(userIds), null, title, body, courseId,
				lessonId, targetId);
	}

	public static NotificationRequest toCourseLearners(Integer courseId, LearningNotification.Type type, String eventKey,
			String title, String body, Integer lessonId, Long targetId) {
		return new NotificationRequest(type, eventKey, null, null, courseId, title, body, courseId, lessonId,
				targetId);
	}
}
