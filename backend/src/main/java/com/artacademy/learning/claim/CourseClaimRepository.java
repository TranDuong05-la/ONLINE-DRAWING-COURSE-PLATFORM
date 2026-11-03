package com.artacademy.learning.claim;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface CourseClaimRepository extends JpaRepository<CourseClaim, Long> {

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from CourseClaim c where c.orderId = :orderId and c.courseId = :courseId")
	Optional<CourseClaim> findForUpdate(@Param("orderId") Integer orderId, @Param("courseId") Integer courseId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from CourseClaim c where c.activeKey = :activeKey")
	Optional<CourseClaim> findActiveForUpdate(@Param("activeKey") String activeKey);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select c from CourseClaim c where c.orderItemId = :orderItemId order by c.courseId")
	List<CourseClaim> findByOrderItemForUpdate(@Param("orderItemId") Integer orderItemId);

	List<CourseClaim> findByOrderItemIdOrderByCourseIdAsc(Integer orderItemId);

	@Query("""
			select c.courseId from CourseClaim c
			where c.learnerProfileId = :profileId and c.status = com.artacademy.learning.claim.CourseClaim.Status.RESERVED
			  and c.expiresAt > :observedAt and c.courseId in :courseIds
			""")
	List<Integer> findActivePendingCourseIds(@Param("profileId") Integer profileId,
			@Param("courseIds") Collection<Integer> courseIds, @Param("observedAt") LocalDateTime observedAt);
}
