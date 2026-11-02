package com.artacademy.learning;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface LessonProgressRepository extends JpaRepository<LessonProgress, Long> {

	Optional<LessonProgress> findByLearnerProfileIdAndLessonId(Integer learnerProfileId, Long lessonId);

	List<LessonProgress> findByLearnerProfileIdAndLessonIdIn(Integer learnerProfileId, Collection<Long> lessonIds);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from LessonProgress p where p.learnerProfileId = :profileId and p.lessonId = :lessonId")
	Optional<LessonProgress> findForUpdate(@Param("profileId") Integer profileId, @Param("lessonId") Long lessonId);
}
