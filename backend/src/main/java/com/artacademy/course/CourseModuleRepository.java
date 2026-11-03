package com.artacademy.course;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseModuleRepository extends JpaRepository<CourseModule, Long> {

    /** Luôn lấy theo thứ tự sort_order (id làm tiêu chí phụ để thứ tự ổn định khi trùng). */
    List<CourseModule> findByCourseIdOrderBySortOrderAscIdAsc(Long courseId);

    @Query("select coalesce(max(m.sortOrder), 0) from CourseModule m where m.course.id = :courseId")
    int maxSortOrder(@Param("courseId") Long courseId);
}
