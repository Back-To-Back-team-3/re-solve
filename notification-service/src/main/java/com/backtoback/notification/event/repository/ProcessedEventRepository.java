package com.backtoback.notification.event.repository;

import java.time.LocalDateTime;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.backtoback.notification.event.domain.ProcessedEvent;

/**
 * 처리 완료 이벤트 영속성 리포지토리 (ERD 3.2 processed_events, 스케줄러 명세서 0.2.3).
 */
public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, Long> {

    boolean existsByEventId(String eventId);

    /**
     * 보관 기간(14일)이 지난 소비 이벤트 기록을 처리 시각 오름차순으로 지정된 건수만큼 물리 삭제한다 (스케줄러 명세서 0.2.3).
     *
     * @param cutoff 삭제 기준 시각 ({@code processed_at < cutoff})
     * @param limit 1회 삭제 상한 건수
     * @return 삭제된 행 수
     */
    @Modifying(clearAutomatically = true)
    @Query(
        value = "DELETE FROM processed_events WHERE processed_at < :cutoff ORDER BY processed_at ASC LIMIT :limit",
        nativeQuery = true
    )
    int deleteExpiredProcessedEvents(@Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);
}
