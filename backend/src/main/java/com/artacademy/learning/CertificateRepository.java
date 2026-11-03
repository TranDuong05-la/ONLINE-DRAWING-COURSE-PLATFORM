package com.artacademy.learning;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CertificateRepository extends JpaRepository<Certificate, Long> {

	Optional<Certificate> findByLearnerProfileIdAndCourseId(Integer learnerProfileId, Integer courseId);

	boolean existsByCertificateCode(String certificateCode);

	List<Certificate> findByLearnerProfileIdOrderByIssuedAtDesc(Integer learnerProfileId);

	/** Admin: mọi chứng nhận đã cấp, lọc theo khóa và tìm theo tên học viên / mã chứng nhận. */
	@Query("""
			select c from Certificate c
			where (:courseId is null or c.courseId = :courseId)
			  and (:q is null or lower(c.learnerName) like :q or lower(c.certificateCode) like :q)
			order by c.issuedAt desc, c.id desc
			""")
	Page<Certificate> search(@Param("courseId") Integer courseId, @Param("q") String q, Pageable pageable);
}
