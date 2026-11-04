package com.artacademy.learning.comment;

import java.util.Collection;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import com.artacademy.enums.Role;

public interface LessonCommentRepository extends JpaRepository<LessonComment, Long> {
	List<LessonComment> findByLessonIdAndParentIdIsNullOrderByCreatedAtDescIdDesc(Long lessonId);
	List<LessonComment> findByParentIdInOrderByCreatedAtAscIdAsc(Collection<Long> parentIds);
	long countByLessonId(Long lessonId);
	List<LessonComment> findByLessonIdOrderByIdDesc(Long lessonId);
	@Query("select c from LessonComment c where c.parentId is null "
			+ "and c.authorRole = com.artacademy.enums.Role.USER "
			+ "and (:unansweredOnly = false or not exists (select r.id from LessonComment r where r.parentId = c.id and r.authorRole in :staffRoles)) "
			+ "order by c.createdAt desc, c.id desc")
	Page<LessonComment> inbox(@Param("unansweredOnly") boolean unansweredOnly,
			@Param("staffRoles") Collection<Role> staffRoles, Pageable pageable);
	@Query("select count(c) from LessonComment c where c.parentId is null "
			+ "and c.authorRole = com.artacademy.enums.Role.USER "
			+ "and not exists (select r.id from LessonComment r where r.parentId = c.id and r.authorRole in :staffRoles)")
	long countUnanswered(@Param("staffRoles") Collection<Role> staffRoles);
}
