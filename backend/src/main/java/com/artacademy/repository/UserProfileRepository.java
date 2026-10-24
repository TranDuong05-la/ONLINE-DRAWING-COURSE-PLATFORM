package com.artacademy.repository;

import com.artacademy.entity.UserProfile;
import com.artacademy.enums.ProfileType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface UserProfileRepository extends JpaRepository<UserProfile, Integer> {

    List<UserProfile> findByUserId(Integer userId);

    Optional<UserProfile> findByIdAndUserId(Integer id, Integer userId);

    Optional<UserProfile> findByUserIdAndProfileType(Integer userId, ProfileType profileType);

    List<UserProfile> findByProfileTypeAndIsApproved(ProfileType profileType, Boolean isApproved);
}
