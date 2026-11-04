package com.backtoback.study.membership.repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.membership.domain.MembershipRole;
import com.backtoback.study.membership.domain.MembershipStatus;
import com.backtoback.study.membership.domain.StudyMembership;

import jakarta.persistence.LockModeType;

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

    /**
     * PENDING 상태인 경우에만 EXPIRED로 전이하고 expires_at을 null로 초기화한다(스케줄러 명세서 §5.1 동시성 설계).
     * 0이면 다른 인스턴스가 먼저 처리했거나 상태가 변경된 것이므로 호출자가 건너뛰어야 한다.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "UPDATE study_memberships SET status = 'EXPIRED', expires_at = NULL, updated_at = NOW(6) "
            + "WHERE id = :id AND status = 'PENDING'",
        nativeQuery = true
    )
    int expireIfPending(@Param("id") Long id);

    /**
     * 만료 시각이 지난 PENDING 신청의 ID를 오래된 순으로 최대 {@code limit}건 조회한다(스케줄러 명세서 §5.1).
     * 잠금 없이 대상 목록을 선정하고 각 건은 expireIfPending으로 조건부 UPDATE한다.
     *
     * @param now 기준 시각 (스케줄러가 주입한 Clock 기반)
     * @param limit 최대 조회 건수
     * @return 만료 처리 대상 membership ID 목록
     */
    @Query(
        value = "SELECT id FROM study_memberships " + "WHERE status = 'PENDING' AND expires_at <= :now "
            + "ORDER BY expires_at ASC " + "LIMIT :limit",
        nativeQuery = true
    )
    List<Long> findExpiredPendingIds(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * 지정 스터디의 지정 상태 가입 행을 잠금 읽기(FOR SHARE)로 조회한다(스케줄러 §5.2 동시성 설계 포인트).
     * REPEATABLE READ 스냅샷 회피를 위해 FOR SHARE를 사용한다.
     */
    @Lock(LockModeType.PESSIMISTIC_READ)
    @Query("SELECT m FROM StudyMembership m WHERE m.studyId = :studyId AND m.status = :status")
    List<StudyMembership> findByStudyIdAndStatusWithLock(
        @Param("studyId") Long studyId,
        @Param("status") MembershipStatus status
    );

    /**
     * 지정 스터디에서 주어진 역할 목록에 해당하는 APPROVED 멤버의 memberId 목록을 반환한다(스케줄러 §5.2 CLOSE 동작 3단계).
     * AssignmentIncompleteRepeated 이벤트의 recipientMemberIds 구성에 사용한다.
     */
    @Query(
        "SELECT m.memberId FROM StudyMembership m "
            + "WHERE m.studyId = :studyId AND m.role IN :roles AND m.status = :status"
    )
    List<Long> findMemberIdsByStudyIdAndRolesAndStatus(
        @Param("studyId") Long studyId,
        @Param("roles") Collection<MembershipRole> roles,
        @Param("status") MembershipStatus status
    );
}
