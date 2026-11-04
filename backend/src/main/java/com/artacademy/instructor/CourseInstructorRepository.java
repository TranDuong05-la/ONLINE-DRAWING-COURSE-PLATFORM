package com.artacademy.instructor;

import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseInstructorRepository extends JpaRepository<CourseInstructor, Integer> {

    boolean existsByCourseIdAndUserId(Integer courseId, Integer userId);

    Optional<CourseInstructor> findByCourseIdAndUserId(Integer courseId, Integer userId);

    List<CourseInstructor> findByCourseIdOrderByAssignedAtAscIdAsc(Integer courseId);

    @Query("select ci.courseId from CourseInstructor ci where ci.userId = :userId")
    List<Integer> courseIdsOf(@Param("userId") Integer userId);
}
