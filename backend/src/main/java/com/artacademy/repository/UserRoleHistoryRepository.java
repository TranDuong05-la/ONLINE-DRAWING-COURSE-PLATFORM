package com.artacademy.repository;

import com.artacademy.entity.UserRoleHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface UserRoleHistoryRepository extends JpaRepository<UserRoleHistory, Integer> {

    @Query("SELECT h FROM UserRoleHistory h JOIN FETCH h.user JOIN FETCH h.changedBy WHERE h.user.id = :userId ORDER BY h.changedAt DESC")
    List<UserRoleHistory> findByUserIdOrderByChangedAtDesc(@Param("userId") Integer userId);
}
