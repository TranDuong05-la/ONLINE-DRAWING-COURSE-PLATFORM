package com.artacademy.learning.notification;

import java.time.LocalDateTime;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import com.artacademy.course.CourseLesson;
import com.artacademy.course.CourseLessonRepository;
import com.artacademy.course.CourseStatus;
import com.artacademy.learning.Enrollment;
import com.artacademy.learning.EnrollmentRepository;
import com.artacademy.learning.Errors;

/**
 * Thông báo trong app (chuông ở trang chủ).
 *
 * <p>Nghiệp vụ chỉ PHÁT {@link NotificationRequest}; thông báo được ghi ở luồng nền SAU KHI transaction nghiệp vụ
 * commit, trong transaction riêng. Ghi thông báo lỗi không làm hỏng việc chấm bài / cấp chứng nhận / cấp quyền.
 * Mỗi người chỉ nhận một thông báo cho mỗi sự kiện (unique user_id + event_key).
 */
@Service
public class NotificationService {
	private static final Logger log = LoggerFactory.getLogger(NotificationService.class);
	private static final int PAGE = 50;

	private final LearningNotificationRepository notifications;
	private final EnrollmentRepository enrollments;
	private final CourseLessonRepository lessons;
	private final TransactionTemplate tx;

	public NotificationService(LearningNotificationRepository notifications, EnrollmentRepository enrollments,
			CourseLessonRepository lessons, PlatformTransactionManager txManager) {
		this.notifications = notifications;
		this.enrollments = enrollments;
		this.lessons = lessons;
		this.tx = new TransactionTemplate(txManager);
	}

	// ---------------------------------------------------------------- ghi

	@Async
	@TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
	public void onRequest(NotificationRequest r) {
		try {
			tx.executeWithoutResult(s -> deliver(r));
		} catch (RuntimeException e) {
			log.warn("Could not create notification {}: {}", r.eventKey(), e.getMessage());
		}
	}

	/** Video bài học vừa được thêm/đổi (từ {@link LessonVideoListener}): báo cho học viên của khóa. */
	@Async
	@EventListener // phát ra sau khi bài học đã commit (Hibernate post-commit), xử lý ở luồng nền
	public void onLessonVideo(LessonVideoChanged event) {
		try {
			tx.executeWithoutResult(s -> {
				CourseLesson l = lessons.findById(event.lessonId()).orElse(null);
				if (l == null || !l.isVisible() || l.getVideoUrl() == null || l.getVideoUrl().isBlank()
						|| l.getModule().getCourse().getStatus() == CourseStatus.DRAFT) {
					return;
				}
				Integer courseId = l.getModule().getCourse().getId();
				deliver(NotificationRequest.toCourseLearners(courseId, LearningNotification.Type.NEW_LESSON_VIDEO,
						"VIDEO:" + l.getId() + ":" + Integer.toHexString(l.getVideoUrl().hashCode()),
						"Video mới trong khóa học", l.getTitle() + " • " + l.getModule().getCourse().getTitle(),
						l.getId(), l.getId().longValue()));
			});
		} catch (RuntimeException e) {
			log.warn("Could not create lesson video notification {}: {}", event.lessonId(), e.getMessage());
		}
	}

	private void deliver(NotificationRequest r) {
		List<Integer> recipients = r.userId() != null ? List.of(r.userId())
				: r.userIds() != null ? r.userIds().stream().distinct().toList()
				: enrollments.findByCourseIdAndStatus(r.fanOutCourseId(), Enrollment.Status.ACTIVE).stream()
						.map(Enrollment::getUserId).distinct().toList();
		for (Integer userId : recipients) {
			if (notifications.existsByUserIdAndEventKey(userId, r.eventKey())) {
				continue;
			}
			LearningNotification n = new LearningNotification();
			n.setUserId(userId);
			n.setType(r.type());
			n.setEventKey(r.eventKey());
			n.setTitle(truncate(r.title(), 200));
			n.setBody(r.body() == null ? null : truncate(r.body(), 1000));
			n.setCourseId(r.courseId());
			n.setLessonId(r.lessonId());
			n.setTargetId(r.targetId());
			notifications.save(n);
		}
	}

	// ---------------------------------------------------------------- đọc

	@Transactional(readOnly = true)
	public List<LearningNotification> list(Integer userId) {
		return notifications.findByUserIdOrderByCreatedAtDescIdDesc(userId, PageRequest.of(0, PAGE));
	}

	@Transactional(readOnly = true)
	public long unreadCount(Integer userId) {
		return notifications.countByUserIdAndReadAtIsNull(userId);
	}

	@Transactional
	public LearningNotification markRead(Integer userId, Long id) {
		LearningNotification n = notifications.findByIdAndUserId(id, userId)
				.orElseThrow(() -> Errors.notFound("Không tìm thấy thông báo"));
		if (n.getReadAt() == null) {
			n.setReadAt(LocalDateTime.now());
		}
		return n;
	}

	@Transactional
	public int markAllRead(Integer userId) {
		return notifications.markAllRead(userId, LocalDateTime.now());
	}

	private static String truncate(String s, int max) {
		return s == null ? "" : s.length() <= max ? s : s.substring(0, max);
	}
}
