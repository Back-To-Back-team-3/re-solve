package com.backtoback.study.assignment.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.assignment.domain.Assignment;
import com.backtoback.study.assignment.domain.AssignmentStatus;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    List<Assignment> findByStudyIdAndStatusIn(Long studyId, List<AssignmentStatus> statuses);

    Page<Assignment> findByStudyId(Long studyId, Pageable pageable);

    Page<Assignment> findByStudyIdAndStatus(Long studyId, AssignmentStatus status, Pageable pageable);

    Optional<Assignment> findByIdAndStudyId(Long id, Long studyId);

    /**
     * SCHEDULED 상태이고 starts_at이 기준 시각 이하인 문제집 ID를 오래된 순으로 최대 limit건 조회한다(스케줄러 §5.2).
     */
    @Query(
        value = "SELECT id FROM assignments WHERE status = 'SCHEDULED' AND starts_at <= :now "
            + "ORDER BY starts_at ASC LIMIT :limit",
        nativeQuery = true
    )
    List<Long> findScheduledToOpenIds(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * SCHEDULED 상태인 경우에만 OPEN으로 전이한다(스케줄러 §5.2 동시성 guard).
     * 0이면 다른 인스턴스가 먼저 전이했거나 상태가 변경된 것이므로 호출자가 건너뛰어야 한다.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "UPDATE assignments SET status = 'OPEN', updated_at = NOW(6) WHERE id = :id AND status = 'SCHEDULED'",
        nativeQuery = true
    )
    int openIfScheduled(@Param("id") Long id);

    /**
     * OPEN이고 deadline_at이 기준 시각 이하인 문제집 ID를 오래된 순(deadline_at ASC, id ASC)으로 최대 limit건 조회한다(스케줄러 §5.2).
     */
    @Query(
        value = "SELECT id FROM assignments WHERE status = 'OPEN' AND deadline_at IS NOT NULL AND deadline_at <= :now "
            + "ORDER BY deadline_at ASC, id ASC LIMIT :limit",
        nativeQuery = true
    )
    List<Long> findScheduledToCloseIds(@Param("now") LocalDateTime now, @Param("limit") int limit);

    /**
     * OPEN 상태이고 deadline_at이 기준 시각 이하인 경우에만 CLOSED로 전이한다(스케줄러 §5.2 동시성 guard).
     * 0이면 다른 인스턴스가 먼저 전이했거나 상태가 변경된 것이므로 호출자가 건너뛰어야 한다.
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "UPDATE assignments SET status = 'CLOSED', closed_at = NOW(6), updated_at = NOW(6) "
            + "WHERE id = :id AND status = 'OPEN' AND deadline_at <= :now",
        nativeQuery = true
    )
    int closeIfOpen(@Param("id") Long id, @Param("now") LocalDateTime now);

    /**
     * 동일 스터디에서 현재 문제집보다 먼저 마감된 문제집 중 가장 최근 것의 ID를 반환한다(스케줄러 §5.2 연속 INCOMPLETE 판정).
     * deadline_at이 같으면 id 오름차순 기준으로 앞선 문제집을 직전으로 간주한다(findScheduledToCloseIds 처리 순서와 동일).
     */
    @Query(
        value = "SELECT id FROM assignments WHERE study_id = :studyId AND status = 'CLOSED' "
            + "AND (deadline_at < :deadlineAt OR (deadline_at = :deadlineAt AND id < :assignmentId)) "
            + "ORDER BY deadline_at DESC, id DESC LIMIT 1",
        nativeQuery = true
    )
    Optional<Long> findPreviousClosedId(
        @Param("studyId") Long studyId,
        @Param("deadlineAt") LocalDateTime deadlineAt,
        @Param("assignmentId") Long assignmentId
    );
}
