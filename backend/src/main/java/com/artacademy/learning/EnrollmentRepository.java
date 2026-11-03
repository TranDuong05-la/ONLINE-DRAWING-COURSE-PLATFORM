package com.artacademy.learning;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface EnrollmentRepository extends JpaRepository<Enrollment, Long> {

	Optional<Enrollment> findByLearnerProfileIdAndCourseId(Integer learnerProfileId, Integer courseId);

	List<Enrollment> findByLearnerProfileIdAndStatusOrderByEnrolledAtDescIdDesc(Integer learnerProfileId,
			Enrollment.Status status);

	List<Enrollment> findByLearnerProfileIdAndStatusAndCourseIdIn(Integer learnerProfileId, Enrollment.Status status,
			Collection<Integer> courseIds);

	List<Enrollment> findBySourceOrderItemIdAndStatus(Integer sourceOrderItemId, Enrollment.Status status);

	List<Enrollment> findByCourseIdAndStatus(Integer courseId, Enrollment.Status status);

	List<Enrollment> findByCourseIdOrderByEnrolledAtDescIdDesc(Integer courseId);

	/** SELECT ... FOR UPDATE: tuần tự hóa việc xét hoàn thành / cấp chứng nhận / thu hồi cho cùng học viên + khóa. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select e from Enrollment e where e.learnerProfileId = :profileId and e.courseId = :courseId")
	Optional<Enrollment> findForUpdate(@Param("profileId") Integer profileId, @Param("courseId") Integer courseId);
}
