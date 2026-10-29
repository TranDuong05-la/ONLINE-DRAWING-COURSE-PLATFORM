package com.artacademy.course;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CourseKitRepository extends JpaRepository<CourseIncludedKit, Long> {

    @Query("select k from CourseIncludedKit k join fetch k.product where k.course.id = :courseId order by k.id")
    List<CourseIncludedKit> findByCourse(@Param("courseId") Long courseId);

    /** Id các khóa có ít nhất 1 món quà còn bán (sản phẩm active). Dùng để hiện nhãn "Tặng kèm dụng cụ". */
    @Query("select distinct k.course.id from CourseIncludedKit k "
            + "where k.course.id in :courseIds and k.product.active = true")
    List<Long> courseIdsWithActiveKits(@Param("courseIds") Collection<Long> courseIds);

    /**
     * Xóa hết quà của khóa bằng câu DELETE chạy ngay.
     * Lý do: Hibernate luôn chạy INSERT trước DELETE khi flush, nếu thêm lại cùng sản phẩm sẽ vi phạm
     * ràng buộc unique (course_id, product_id).
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from CourseIncludedKit k where k.course.id = :courseId")
    void deleteByCourse(@Param("courseId") Long courseId);
}
