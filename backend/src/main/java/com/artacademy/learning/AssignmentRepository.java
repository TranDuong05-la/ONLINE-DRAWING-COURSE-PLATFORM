package com.artacademy.learning;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

	Optional<Assignment> findByLessonId(Integer lessonId);

	Optional<Assignment> findByLessonIdAndActiveTrue(Integer lessonId);

	List<Assignment> findByLessonIdInAndActiveTrue(Collection<Integer> lessonIds);

	List<Assignment> findByLessonIdIn(Collection<Integer> lessonIds);
}
