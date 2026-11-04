package com.artacademy.learning;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface SubmissionRepository extends JpaRepository<AssignmentSubmission, Long> {

	List<AssignmentSubmission> findByAssignmentIdAndLearnerProfileIdOrderByAttemptNoDesc(Long assignmentId,
			Integer learnerProfileId);

	Optional<AssignmentSubmission> findFirstByAssignmentIdAndLearnerProfileIdOrderByAttemptNoDesc(Long assignmentId,
			Integer learnerProfileId);

	List<AssignmentSubmission> findByLearnerProfileIdAndAssignmentIdIn(Integer learnerProfileId,
			Collection<Long> assignmentIds);

	long countByStatus(AssignmentSubmission.Status status);

	List<AssignmentSubmission> findTop20ByLearnerProfileIdAndReviewedAtIsNotNullOrderByReviewedAtDesc(
			Integer learnerProfileId);

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from AssignmentSubmission s where s.id = :id")
	Optional<AssignmentSubmission> findForUpdate(@Param("id") Long id);

	@Query("""
			select s from AssignmentSubmission s
			where (:courseId is null or s.courseId = :courseId)
			  and (:status is null or s.status = :status)
			order by s.submittedAt desc, s.id desc
			""")
	Page<AssignmentSubmission> search(@Param("courseId") Integer courseId,
			@Param("status") AssignmentSubmission.Status status, Pageable pageable);

	@Query("select distinct s.courseId from AssignmentSubmission s")
	List<Integer> distinctCourseIds();

	// ---- giảng viên: chỉ bài nộp của các khóa được phân công

	@Query("""
			select s from AssignmentSubmission s
			where s.courseId in :courseIds
			  and (:courseId is null or s.courseId = :courseId)
			  and (:status is null or s.status = :status)
			order by s.submittedAt desc, s.id desc
			""")
	Page<AssignmentSubmission> searchInCourses(@Param("courseIds") Collection<Integer> courseIds,
			@Param("courseId") Integer courseId, @Param("status") AssignmentSubmission.Status status,
			Pageable pageable);

	long countByCourseIdInAndStatus(Collection<Integer> courseIds, AssignmentSubmission.Status status);

	@Query("select distinct s.courseId from AssignmentSubmission s where s.courseId in :courseIds")
	List<Integer> distinctCourseIdsIn(@Param("courseIds") Collection<Integer> courseIds);
}
