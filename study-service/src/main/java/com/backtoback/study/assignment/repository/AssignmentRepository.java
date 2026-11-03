package com.backtoback.study.assignment.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.study.assignment.domain.Assignment;
import com.backtoback.study.assignment.domain.AssignmentStatus;

public interface AssignmentRepository extends JpaRepository<Assignment, Long> {

    List<Assignment> findByStudyIdAndStatusIn(Long studyId, List<AssignmentStatus> statuses);

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
}
