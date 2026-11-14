package com.backtoback.integration.infra.event.repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.integration.infra.event.domain.ProcessedEvent;

/**
 * ProcessedEvent JPA 리포지토리 (erd.md > 3.2, scheduler.md > 0.2.3).
 */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    /**
     * 특정 이벤트 ID의 처리 완료 여부를 확인한다 (멱등성 보장).
     *
     * @param eventId 이벤트 식별자
     * @return 이미 처리된 경우 true
     */
    boolean existsByEventId(String eventId);

    /**
     * 처리된 이벤트를 조건부 삽입한다 (동일 event_id 존재 시 무시).
     * 중복 시 유니크 제약 예외를 발생시키지 않아 상위 트랜잭션이 rollback-only로 오염되는 것을 방지한다.
     *
     * @param eventId 이벤트 식별자
     * @param eventType 이벤트 타입
     * @param processedAt 처리 시각
     * @return 영향받은 행 수
     */
    @Modifying
    @Query(
        value = """
            INSERT INTO processed_events (event_id, event_type, processed_at, created_at)
            VALUES (:eventId, :eventType, :processedAt, :processedAt)
            ON DUPLICATE KEY UPDATE event_id = event_id
            """,
        nativeQuery = true
    )
    int insertIfAbsent(
        @Param("eventId") String eventId,
        @Param("eventType") String eventType,
        @Param("processedAt") LocalDateTime processedAt
    );

    Optional<ProcessedEvent> findByEventId(String eventId);

    /**
     * 보관 기간(14일)이 경과한 소비 이벤트 기록 ID 목록을 조회한다 (scheduler.md > 0.2.3).
     *
     * @param threshold 보관 기한 기준 시각
     * @param limit 1회 조회 상한 건수
     * @return 삭제 대상 ID 목록
     */
    @Query(
        value = """
            SELECT id FROM processed_events
            WHERE processed_at < :threshold
            ORDER BY id ASC
            LIMIT :limit
            """,
        nativeQuery = true
    )
    List<Long> findExpiredProcessedEventIds(@Param("threshold") LocalDateTime threshold, @Param("limit") int limit);

    /**
     * 지정된 ID 목록의 소비 이벤트 기록을 일괄 삭제한다 (scheduler.md > 0.2.3).
     *
     * @param ids 삭제 대상 ID 목록
     * @return 삭제된 건수
     */
    @Modifying(
        clearAutomatically = true,
        flushAutomatically = true
    )
    @Query("DELETE FROM ProcessedEvent e WHERE e.id IN :ids")
    int deleteByIdIn(@Param("ids") List<Long> ids);
}
