package com.backtoback.study.membership.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.study.membership.domain.StudyMemberQuota;

public interface StudyMemberQuotaRepository extends JpaRepository<StudyMemberQuota, Long> {

    Optional<StudyMemberQuota> findByMemberId(Long memberId);
}
