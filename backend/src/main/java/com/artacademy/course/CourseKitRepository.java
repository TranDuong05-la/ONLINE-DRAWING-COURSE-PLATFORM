package com.artacademy.course;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseKitRepository extends JpaRepository<CourseIncludedKit, Integer> {

    @Query("select k from CourseIncludedKit k where k.course.id = :courseId order by k.id")
    List<CourseIncludedKit> findByCourse(@Param("courseId") Integer courseId);

    /** Quà của nhiều khóa trong 1 câu truy vấn (việc kiểm tra sản phẩm còn bán do ProductCatalog làm). */
    @Query("select k from CourseIncludedKit k where k.course.id in :courseIds order by k.id")
    List<CourseIncludedKit> findByCourses(@Param("courseIds") Collection<Integer> courseIds);

    /**
     * Xóa hết quà của khóa bằng câu DELETE chạy ngay.
     * Lý do: Hibernate luôn chạy INSERT trước DELETE khi flush, nếu thêm lại cùng sản phẩm sẽ vi phạm
     * ràng buộc unique (course_id, product_id).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from CourseIncludedKit k where k.course.id = :courseId")
    void deleteByCourse(@Param("courseId") Integer courseId);
}
