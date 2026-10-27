package com.backtoback.notification.event.service;

import java.time.Clock;
import java.time.LocalDateTime;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.notification.event.repository.ProcessedEventRepository;

import lombok.RequiredArgsConstructor;

/**
 * 보관 기간(14일)이 지난 소비 이벤트 기록({@code processed_events})을 배치 단위 독립 트랜잭션으로 물리 삭제하는 서비스 (스케줄러 명세서 0.2.3).
 */
@Service
@RequiredArgsConstructor
public class ProcessedEventCleanupService {

    public static final long PROCESSED_EVENT_RETENTION_DAYS = 14L;
    public static final int BATCH_SIZE = 1000;
    public static final int SINGLE_BATCH_TIMEOUT_SECONDS = 10;

    private final ProcessedEventRepository processedEventRepository;
    private final Clock clock;

    /**
     * 소비 처리된 지 14일이 지난 이벤트 멱등 기록을 최대 {@code batchSize}건만큼 단일 배치 트랜잭션으로 물리 삭제한다 (스케줄러 명세서 0.2.3).
     *
     * @param batchSize 1회 삭제 상한 건수
     * @return 삭제된 소비 이벤트 기록 행 수
     */
    @Transactional(timeout = SINGLE_BATCH_TIMEOUT_SECONDS)
    public int deleteExpiredProcessedEventsBatch(int batchSize) {
        LocalDateTime cutoff = LocalDateTime.now(clock).minusDays(PROCESSED_EVENT_RETENTION_DAYS);
        return processedEventRepository.deleteExpiredProcessedEvents(cutoff, batchSize);
    }
}
