package com.backtoback.study.assignment.repository;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.assignment.domain.AssignmentMemberStatus;

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
}
