package com.backtoback.study.membership.repository;

import java.util.Collection;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.StudyMembership;

public interface StudyMembershipRepository extends JpaRepository<StudyMembership, Long> {

    Optional<StudyMembership> findByStudyIdAndRoleAndStatus(Long studyId, MembershipRole role, MembershipStatus status);

    Optional<StudyMembership> findByStudyIdAndMemberIdAndStatusIn(
        Long studyId,
        Long memberId,
        Collection<MembershipStatus> statuses
    );

    Page<StudyMembership> findByMemberIdAndStatusIn(
        Long memberId,
        Collection<MembershipStatus> statuses,
        Pageable pageable
    );
}
