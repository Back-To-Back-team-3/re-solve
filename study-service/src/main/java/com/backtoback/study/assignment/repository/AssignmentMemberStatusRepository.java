package com.backtoback.study.assignment.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.assignment.domain.AssignmentMemberStatus;
import com.backtoback.study.assignment.domain.MemberProgressStatus;

public interface AssignmentMemberStatusRepository extends JpaRepository<AssignmentMemberStatus, Long> {

    /**
     * 이미 있으면 무시한다. 영향 행 수(1=삽입, 0=중복 무시)를 반환한다(ERD §6.5 uk_assignment_member_statuses_assignment_member).
     * completedAt은 COMPLETED 상태일 때만 전달하고 그 외에는 null을 넘긴다(ERD §4.5.7 completed_at 불변식).
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "INSERT IGNORE INTO assignment_member_statuses " + "(assignment_id, member_id, membership_id, status, "
            + "completed_count, on_time_count, completed_at, created_at, updated_at) "
            + "VALUES (:assignmentId, :memberId, :membershipId, :status, "
            + ":completedCount, :onTimeCount, :completedAt, NOW(6), NOW(6))",
        nativeQuery = true
    )
    int insertIgnore(
        @Param("assignmentId") Long assignmentId,
        @Param("memberId") Long memberId,
        @Param("membershipId") Long membershipId,
        @Param("status") String status,
        @Param("completedCount") int completedCount,
        @Param("onTimeCount") int onTimeCount,
        @Param("completedAt") LocalDateTime completedAt
    );

    /**
     * COMPLETED가 아닌 멤버 상태를 INCOMPLETE로 일괄 전이한다(스케줄러 §5.2 CLOSE 동작 2단계).
     * 탈퇴한 멤버(study_memberships.status IN LEFT·REMOVED)는 제외한다.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "UPDATE assignment_member_statuses ams "
            + "INNER JOIN study_memberships sm ON sm.id = ams.membership_id "
            + "SET ams.status = 'INCOMPLETE', ams.updated_at = NOW(6) " + "WHERE ams.assignment_id = :assignmentId "
            + "AND ams.status != 'COMPLETED' " + "AND sm.status NOT IN ('LEFT', 'REMOVED')",
        nativeQuery = true
    )
    int updateToIncompleteExcludingLeft(@Param("assignmentId") Long assignmentId);

    /**
     * 이미 편입된 멤버의 진행 상태·완료 수·완료 시각을 재계산 값으로 갱신한다(스케줄러 §5.2 OPEN 동작 upsert).
     * INSERT IGNORE가 0행이면 호출해 기존 NOT_STARTED 행을 갱신한다.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "UPDATE assignment_member_statuses "
            + "SET status = :status, completed_count = :completedCount, on_time_count = :onTimeCount, "
            + "completed_at = :completedAt, updated_at = NOW(6) "
            + "WHERE assignment_id = :assignmentId AND member_id = :memberId",
        nativeQuery = true
    )
    int updateStatusAndCounts(
        @Param("assignmentId") Long assignmentId,
        @Param("memberId") Long memberId,
        @Param("status") String status,
        @Param("completedCount") int completedCount,
        @Param("onTimeCount") int onTimeCount,
        @Param("completedAt") LocalDateTime completedAt
    );

    /**
     * 지정 문제집에서 주어진 상태인 멤버 ID 목록을 반환한다(스케줄러 §5.2 CLOSE 동작 2·3단계).
     */
    @Query(
        "SELECT a.memberId FROM AssignmentMemberStatus a WHERE a.assignmentId = :assignmentId AND a.status = :status"
    )
    List<Long> findMemberIdsByAssignmentIdAndStatus(
        @Param("assignmentId") Long assignmentId,
        @Param("status") MemberProgressStatus status
    );

    /**
     * 지정 문제집에서 주어진 멤버 중 특정 상태인 멤버 ID 목록을 반환한다(스케줄러 §5.2 연속 INCOMPLETE 판정).
     */
    @Query(
        "SELECT a.memberId FROM AssignmentMemberStatus a "
            + "WHERE a.assignmentId = :assignmentId AND a.memberId IN :memberIds AND a.status = :status"
    )
    List<Long> findMemberIdsByAssignmentIdAndMemberIdsAndStatus(
        @Param("assignmentId") Long assignmentId,
        @Param("memberIds") List<Long> memberIds,
        @Param("status") MemberProgressStatus status
    );

    /**
     * 한 문제집에서 한 멤버의 진행 상태를 조회한다. 재채점 재계산 시 현재 상태 확인에 사용한다(ERD 4.5.7).
     */
    java.util.Optional<AssignmentMemberStatus> findByAssignmentIdAndMemberId(Long assignmentId, Long memberId);

    /**
     * 여러 문제집에서 한 멤버의 진행 상태를 일괄 조회한다 (API 명세서 §5.4.2 myStatus).
     */
    List<AssignmentMemberStatus> findByAssignmentIdInAndMemberId(List<Long> assignmentIds, Long memberId);

    /**
     * 한 문제집에서 주어진 멤버들의 진행 상태를 조회한다 (API 명세서 §5.4.3 memberProgress).
     */
    List<AssignmentMemberStatus> findByAssignmentIdAndMemberIdIn(Long assignmentId, List<Long> memberIds);
}
