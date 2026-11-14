package com.backtoback.member.member.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.member.member.domain.LearningProfile;

public interface LearningProfileRepository extends JpaRepository<LearningProfile, Long> {

    Optional<LearningProfile> findByMemberId(Long memberId);

    boolean existsByMemberIdAndGoalIsNotNull(Long memberId);
}
