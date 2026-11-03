package com.artacademy.learning.notification;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LearningNotificationRepository extends JpaRepository<LearningNotification, Long> {

	boolean existsByUserIdAndEventKey(Integer userId, String eventKey);

	List<LearningNotification> findByUserIdOrderByCreatedAtDescIdDesc(Integer userId, Pageable pageable);

	long countByUserIdAndReadAtIsNull(Integer userId);

	Optional<LearningNotification> findByIdAndUserId(Long id, Integer userId);

	@Modifying
	@Query("update LearningNotification n set n.readAt = :at where n.userId = :userId and n.readAt is null")
	int markAllRead(@Param("userId") Integer userId, @Param("at") LocalDateTime at);
}
