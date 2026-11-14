package com.backtoback.integration.infra.event.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.integration.infra.event.repository.OutboxEventRepository;
import com.backtoback.integration.infra.event.repository.ProcessedEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 보관 기간이 경과한 발행 완료 Outbox 이벤트 및 소비 기록을 배치 단위로 삭제하는 서비스 (scheduler.md > 0.2.2, 0.2.3).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EventCleanupService {

    public static final int BATCH_SIZE = 1000;
    public static final int OUTBOX_RETENTION_DAYS = 7;
    public static final int PROCESSED_EVENT_RETENTION_DAYS = 14;
    private static final ZoneId ZONE_SEOUL = ZoneId.of("Asia/Seoul");

    private final OutboxEventRepository outboxEventRepository;
    private final ProcessedEventRepository processedEventRepository;
    private final Clock clock;

    /**
     * 7일 이상 경과한 PUBLISHED 상태의 Outbox 이벤트를 지정된 배치 크기만큼 삭제한다 (scheduler.md > 0.2.2).
     *
     * @param batchSize 1회 삭제 상한 건수
     * @return 삭제된 건수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteExpiredPublishedEventsBatch(int batchSize) {
        LocalDateTime threshold = LocalDateTime.ofInstant(clock.instant(), ZONE_SEOUL).minusDays(OUTBOX_RETENTION_DAYS);

        List<Long> expiredIds = outboxEventRepository.findExpiredPublishedEventIds(threshold, batchSize);
        if (expiredIds.isEmpty()) {
            return 0;
        }

        int deletedCount = outboxEventRepository.deleteByIdIn(expiredIds);
        log.debug("만료 Outbox 이벤트(7일 경과) 배치 삭제: count={}", deletedCount);
        return deletedCount;
    }

    /**
     * 14일 이상 경과한 소비 이벤트 기록(processed_events)을 지정된 배치 크기만큼 삭제한다 (scheduler.md > 0.2.3).
     *
     * @param batchSize 1회 삭제 상한 건수
     * @return 삭제된 건수
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int deleteExpiredProcessedEventsBatch(int batchSize) {
        LocalDateTime threshold
            = LocalDateTime.ofInstant(clock.instant(), ZONE_SEOUL).minusDays(PROCESSED_EVENT_RETENTION_DAYS);

        List<Long> expiredIds = processedEventRepository.findExpiredProcessedEventIds(threshold, batchSize);
        if (expiredIds.isEmpty()) {
            return 0;
        }

        int deletedCount = processedEventRepository.deleteByIdIn(expiredIds);
        log.debug("만료 소비 이벤트(14일 경과) 배치 삭제: count={}", deletedCount);
        return deletedCount;
    }
}
