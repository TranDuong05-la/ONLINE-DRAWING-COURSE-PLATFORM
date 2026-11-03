package com.artacademy.learning.notification;

import java.util.Arrays;
import java.util.Objects;

import org.hibernate.engine.spi.SessionFactoryImplementor;
import org.hibernate.event.service.spi.EventListenerRegistry;
import org.hibernate.event.spi.EventType;
import org.hibernate.event.spi.PostCommitInsertEventListener;
import org.hibernate.event.spi.PostCommitUpdateEventListener;
import org.hibernate.event.spi.PostInsertEvent;
import org.hibernate.event.spi.PostUpdateEvent;
import org.hibernate.persister.entity.EntityPersister;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import com.artacademy.course.CourseLesson;

import jakarta.annotation.PostConstruct;
import jakarta.persistence.EntityManagerFactory;

/**
 * Theo dõi bài học của phần B mà KHÔNG sửa code của B: lắng nghe sự kiện Hibernate sau khi lưu bài học đã commit.
 * Khi bài học được tạo có video, hoặc được gắn/đổi video, phát {@link LessonVideoChanged} để báo cho học viên.
 */
@Component
public class LessonVideoListener implements PostCommitInsertEventListener, PostCommitUpdateEventListener {

	private final EntityManagerFactory emf;
	private final ApplicationEventPublisher events;

	public LessonVideoListener(EntityManagerFactory emf, ApplicationEventPublisher events) {
		this.emf = emf;
		this.events = events;
	}

	@PostConstruct
	void register() {
		EventListenerRegistry registry = emf.unwrap(SessionFactoryImplementor.class).getServiceRegistry()
				.requireService(EventListenerRegistry.class);
		registry.appendListeners(EventType.POST_COMMIT_INSERT, this);
		registry.appendListeners(EventType.POST_COMMIT_UPDATE, this);
	}

	@Override
	public void onPostInsert(PostInsertEvent event) {
		if (event.getEntity() instanceof CourseLesson l && hasText(l.getVideoUrl())) {
			events.publishEvent(new LessonVideoChanged(l.getId()));
		}
	}

	@Override
	public void onPostUpdate(PostUpdateEvent event) {
		if (!(event.getEntity() instanceof CourseLesson l) || !hasText(l.getVideoUrl())) {
			return;
		}
		int i = Arrays.asList(event.getPersister().getPropertyNames()).indexOf("videoUrl");
		Object old = event.getOldState() == null || i < 0 ? null : event.getOldState()[i];
		if (!Objects.equals(old, l.getVideoUrl())) {
			events.publishEvent(new LessonVideoChanged(l.getId()));
		}
	}

	@Override
	public void onPostInsertCommitFailed(PostInsertEvent event) {
	}

	@Override
	public void onPostUpdateCommitFailed(PostUpdateEvent event) {
	}

	@Override
	public boolean requiresPostCommitHandling(EntityPersister persister) {
		return CourseLesson.class.equals(persister.getMappedClass());
	}

	private static boolean hasText(String s) {
		return s != null && !s.isBlank();
	}
}
