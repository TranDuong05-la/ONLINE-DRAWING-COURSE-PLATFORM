package com.artacademy.course;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseLessonRepository extends JpaRepository<CourseLesson, Long> {

    /** Mọi bài của khóa (kể cả bị ẩn), đúng thứ tự module rồi bài. Dùng cho admin. */
    @Query("select l from CourseLesson l join fetch l.module m "
            + "where m.course.id = :courseId "
            + "order by m.sortOrder, m.id, l.sortOrder, l.id")
    List<CourseLesson> findAllByCourse(@Param("courseId") Long courseId);

    /** Chỉ bài hiển thị (bài và module đều không ẩn), đúng thứ tự. Dùng cho học viên. */
    @Query("select l from CourseLesson l join fetch l.module m "
            + "where m.course.id = :courseId and m.hidden = false and l.hidden = false "
            + "order by m.sortOrder, m.id, l.sortOrder, l.id")
    List<CourseLesson> findVisibleByCourse(@Param("courseId") Long courseId);

    List<CourseLesson> findByModuleIdOrderBySortOrderAscIdAsc(Long moduleId);

    @Query("select coalesce(max(l.sortOrder), 0) from CourseLesson l where l.module.id = :moduleId")
    int maxSortOrder(@Param("moduleId") Long moduleId);

    /**
     * Thống kê các bài hiển thị cho nhiều khóa trong 1 câu truy vấn (tránh gọi DB lặp lại cho từng khóa).
     * Mỗi dòng: [courseId, số bài, tổng thời lượng giây].
     */
    @Query("select m.course.id, count(l), coalesce(sum(l.durationSeconds), 0) "
            + "from CourseLesson l join l.module m "
            + "where m.course.id in :courseIds and m.hidden = false and l.hidden = false "
            + "group by m.course.id")
    List<Object[]> visibleStats(@Param("courseIds") Collection<Long> courseIds);
}
