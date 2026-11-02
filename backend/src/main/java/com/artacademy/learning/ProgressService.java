package com.artacademy.learning;

import java.time.Duration;
import java.time.LocalDateTime;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.artacademy.learning.LearningDtos.ProgressResponse;

/**
 * Lưu tiến độ xem video.
 *
 * <p>App gửi vị trí để xem tiếp và số giây ĐÃ PHÁT kể từ lần báo trước. Server chỉ cộng số giây đã phát, và giới
 * hạn theo thời gian thực đã trôi qua (cho phép tốc độ 2x). Tua tới cuối chỉ đổi vị trí xem tiếp, không được tính
 * là đã xem. Bài hoàn thành khi xem đủ 90% thời lượng; đã hoàn thành thì không bao giờ bị "tụt" lại.
 */
@Service
public class ProgressService {
	private static final int FIRST_REPORT_CAP_SECONDS = 30;
	private static final int RETRIES = 3;

	private final LessonAccess access;
	private final LessonProgressRepository progress;
	private final CompletionService completion;
	private final TransactionTemplate tx;
	private final double completeRatio;

	public ProgressService(LessonAccess access, LessonProgressRepository progress, CompletionService completion,
			PlatformTransactionManager txManager, @Value("${app.learning.complete-ratio:0.9}") double completeRatio) {
		this.access = access;
		this.progress = progress;
		this.completion = completion;
		this.tx = new TransactionTemplate(txManager);
		this.completeRatio = completeRatio;
	}

	public ProgressResponse update(Integer userId, Long lessonId, int positionSeconds, int watchedDeltaSeconds) {
		LessonAccess.Granted g = access.requireLesson(userId, lessonId);
		// Lần đầu mở bài, hai request có thể cùng tạo dòng progress -> một cái vi phạm unique. Bắt lỗi và thử lại;
		// lần sau sẽ thấy dòng đã có và chỉ cập nhật.
		for (int attempt = 1;; attempt++) {
			try {
				return tx.execute(s -> doUpdate(g, positionSeconds, watchedDeltaSeconds));
			} catch (DataIntegrityViolationException raceOnFirstInsert) {
				if (attempt >= RETRIES) {
					throw raceOnFirstInsert;
				}
			}
		}
	}

	private ProgressResponse doUpdate(LessonAccess.Granted g, int position, int delta) {
		Integer profileId = g.learnerProfileId();
		LessonProgress p = progress.findForUpdate(profileId, g.lessonId()).orElse(null);
		if (p == null) {
			p = new LessonProgress();
			p.setLearnerProfileId(profileId);
			p.setLessonId(g.lessonId());
			p = progress.saveAndFlush(p);
		}
		int duration = Math.max(0, g.durationSeconds());
		LocalDateTime now = LocalDateTime.now();
		int allowed;
		if (p.getLastWatchedAt() == null) {
			allowed = Math.min(delta, FIRST_REPORT_CAP_SECONDS);
		} else {
			long elapsed = Math.max(0, Duration.between(p.getLastWatchedAt(), now).toSeconds());
			allowed = (int) Math.min(delta, elapsed * 2 + 2);
		}
		int watched = p.getWatchedSeconds() + allowed;
		if (duration > 0) {
			watched = Math.min(watched, duration);
			position = Math.min(position, duration);
		}
		p.setWatchedSeconds(watched);
		p.setLastPositionSeconds(Math.max(0, position));
		p.setLastWatchedAt(now);
		boolean wasCompleted = Boolean.TRUE.equals(p.getCompleted());
		// Bài không có thời lượng (chưa có video): chỉ cần mở và báo một lần là hoàn thành.
		int needed = duration > 0 ? (int) Math.ceil(duration * completeRatio) : 0;
		boolean nowCompleted = wasCompleted || watched >= needed;
		p.setCompleted(nowCompleted);
		if (nowCompleted && !wasCompleted) {
			p.setCompletedAt(now);
		}
		progress.save(p);
		Long courseId = g.courseId();
		CompletionService.CourseProgress cp = (!wasCompleted && nowCompleted) ? completion.evaluate(profileId, courseId)
				: completion.compute(profileId, courseId);
		return new ProgressResponse(g.lessonId(), p.getLastPositionSeconds(), p.getWatchedSeconds(), nowCompleted,
				cp.percent(), cp.complete());
	}
}
