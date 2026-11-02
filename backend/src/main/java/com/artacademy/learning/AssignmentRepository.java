package com.artacademy.learning;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

	Optional<Assignment> findByLessonId(Long lessonId);

	Optional<Assignment> findByLessonIdAndActiveTrue(Long lessonId);

	List<Assignment> findByLessonIdInAndActiveTrue(Collection<Long> lessonIds);

	List<Assignment> findByLessonIdIn(Collection<Long> lessonIds);
}
