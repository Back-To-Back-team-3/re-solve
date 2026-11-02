package com.backtoback.study.membership.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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

    Page<StudyMembership> findByStudyIdAndStatus(Long studyId, MembershipStatus status, Pageable pageable);

    /**
     * PENDING이고 만료되지 않은 경우에만 APPROVED로 전이하고 joined_at을 기록한다.
     * 0이면 재조회 후 상태가 PENDING이 아니면 STUDY_MEMBERSHIP_STATUS_CONFLICT, 만료이면 STUDY_APPLICATION_EXPIRED.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "UPDATE study_memberships "
            + "SET status = 'APPROVED', joined_at = :joinedAt, expires_at = NULL, updated_at = NOW(6) "
            + "WHERE id = :id AND status = 'PENDING' AND expires_at > NOW(6)",
        nativeQuery = true
    )
    int approveIfPending(@Param("id") Long id, @Param("joinedAt") LocalDateTime joinedAt);

    /**
     * PENDING이고 만료되지 않은 경우에만 REJECTED로 전이하고 expires_at을 null로 초기화한다.
     * 0이면 재조회 후 상태가 PENDING이 아니면 STUDY_MEMBERSHIP_STATUS_CONFLICT, 만료이면 STUDY_APPLICATION_EXPIRED.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "UPDATE study_memberships " + "SET status = 'REJECTED', expires_at = NULL, updated_at = NOW(6) "
            + "WHERE id = :id AND status = 'PENDING' AND expires_at > NOW(6)",
        nativeQuery = true
    )
    int rejectIfPending(@Param("id") Long id);

    /**
     * APPROVED 상태인 경우에만 REMOVED로 전이하고 removed_reason·left_at을 기록한다.
     * 0이면 이미 상태가 변경된 것이므로 호출자가 STUDY_MEMBERSHIP_NOT_FOUND를 던져야 한다.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "UPDATE study_memberships "
            + "SET status = 'REMOVED', removed_reason = :reason, left_at = :leftAt, updated_at = NOW(6) "
            + "WHERE id = :id AND status = 'APPROVED'",
        nativeQuery = true
    )
    int removeIfApproved(@Param("id") Long id, @Param("reason") String reason, @Param("leftAt") LocalDateTime leftAt);

    /**
     * PENDING 상태인 경우에만 CANCELED로 전이하고 expires_at을 null로 초기화한다.
     * 0이면 이미 상태가 변경된 것이므로 호출자가 재조회해 STUDY_MEMBERSHIP_NOT_FOUND를 던져야 한다.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "UPDATE study_memberships SET status = 'CANCELED', expires_at = NULL, updated_at = NOW(6) "
            + "WHERE id = :id AND status = 'PENDING'",
        nativeQuery = true
    )
    int cancelIfPending(@Param("id") Long id);

    /**
     * APPROVED 상태인 경우에만 LEFT로 전이하고 left_at을 기록한다.
     * 0이면 이미 상태가 변경된 것이므로 호출자가 재조회해 STUDY_MEMBERSHIP_NOT_FOUND를 던져야 한다.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "UPDATE study_memberships SET status = 'LEFT', left_at = :leftAt, updated_at = NOW(6) "
            + "WHERE id = :id AND status = 'APPROVED'",
        nativeQuery = true
    )
    int leaveIfApproved(@Param("id") Long id, @Param("leftAt") LocalDateTime leftAt);
}
