package com.backtoback.integration.infra.event.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.integration.infra.event.domain.OutboxEvent;

/**
 * OutboxEvent JPA 리포지토리 (erd.md > 3.1, scheduler.md > 0.2.1, 0.2.2).
 */
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    Optional<OutboxEvent> findByEventId(String eventId);

    /**
     * PENDING 상태의 이벤트를 다중 인스턴스 경합 없이 선점하기 위해 SKIP LOCKED로 조회한다 (scheduler.md > 0.2.1).
     *
     * @param limit 최대 조회 건수
     * @return 선점된 OutboxEvent 목록
     */
    @Query(
        value = """
            SELECT * FROM outbox_events
            WHERE status = 'PENDING'
            ORDER BY id ASC
            LIMIT :limit
            FOR UPDATE SKIP LOCKED
            """,
        nativeQuery = true
    )
    List<OutboxEvent> findPendingEventsForUpdateSkipLocked(@Param("limit") int limit);

    /**
     * 발행 완료 후 보관 기간(7일)이 경과한 이벤트의 ID 목록을 조회한다 (scheduler.md > 0.2.2).
     *
     * @param threshold 보관 기한 기준 시각
     * @param limit 1회 조회 상한 건수
     * @return 삭제 대상 ID 목록
     */
    @Query(
        value = """
            SELECT id FROM outbox_events
            WHERE status = 'PUBLISHED' AND published_at < :threshold
            ORDER BY id ASC
            LIMIT :limit
            """,
        nativeQuery = true
    )
    List<Long> findExpiredPublishedEventIds(@Param("threshold") LocalDateTime threshold, @Param("limit") int limit);

    /**
     * 지정된 ID 목록의 Outbox 이벤트를 일괄 삭제한다 (scheduler.md > 0.2.2).
     *
     * @param ids 삭제 대상 ID 목록
     * @return 삭제된 건수
     */
    @Modifying(
        clearAutomatically = true,
        flushAutomatically = true
    )
    @Query("DELETE FROM OutboxEvent e WHERE e.id IN :ids")
    int deleteByIdIn(@Param("ids") List<Long> ids);
}
