package com.artacademy.repository;

import com.artacademy.entity.UserRoleHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface UserRoleHistoryRepository extends JpaRepository<UserRoleHistory, Integer> {

    @Query("select h from UserRoleHistory h join fetch h.user join fetch h.changedBy "
            + "where h.user.id = :userId order by h.changedAt desc, h.id desc")
    List<UserRoleHistory> findHistoryForUser(@Param("userId") Integer userId);
}
