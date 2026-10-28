package com.backtoback.integration.infra.event.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.integration.infra.event.domain.OutboxEvent;
import com.backtoback.integration.infra.event.domain.OutboxEventStatus;
import com.backtoback.integration.infra.event.publisher.EventPublisher;
import com.backtoback.integration.infra.event.repository.OutboxEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * PENDING 상태의 Outbox 이벤트를 SKIP LOCKED로 선점하여 외부 메시지 브로커로 발행하는 서비스 (scheduler.md > 0.2.1).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxRelayService {

    public static final int BATCH_SIZE = 100;
    private static final ZoneId ZONE_SEOUL = ZoneId.of("Asia/Seoul");

    private final OutboxEventRepository outboxEventRepository;
    private final EventPublisher eventPublisher;
    private final Clock clock;

    /**
     * PENDING 상태의 Outbox 이벤트를 최대 100건 선점하여 순차 발행한다.
     * 선점 트랜잭션 안에서 건별 발행 성공 시 PUBLISHED로 전이하고, 실패 시 retry_count 증가(5회 초과 시 FAILED)를 기록한다.
     *
     * @return 처리된 총 이벤트 건수
     */
    @Transactional
    public int relayPendingEvents() {
        List<OutboxEvent> pendingEvents = outboxEventRepository.findPendingEventsForUpdateSkipLocked(BATCH_SIZE);
        if (pendingEvents.isEmpty()) {
            return 0;
        }

        LocalDateTime now = LocalDateTime.ofInstant(clock.instant(), ZONE_SEOUL);
        int successCount = 0;
        int failureCount = 0;

        for (OutboxEvent event : pendingEvents) {
            try {
                eventPublisher.publish(event);
                event.markPublished(now);
                successCount++;
            } catch (Exception exception) {
                failureCount++;
                event.recordFailure(OutboxEvent.DEFAULT_MAX_RETRIES);
                if (event.getStatus() == OutboxEventStatus.FAILED) {
                    log
                        .error(
                            "[OPERATIONAL ALERT] Outbox 이벤트 발행 최종 실패 (FAILED 전이, 수동 복구 필요) - "
                                + "eventId: {}, eventType: {}, aggregateId: {}, retryCount: {}",
                            event.getEventId(),
                            event.getEventType(),
                            event.getAggregateId(),
                            event.getRetryCount(),
                            exception
                        );
                } else {
                    log
                        .warn(
                            "Outbox 이벤트 발행 실패 - eventId: {}, eventType: {}, retryCount: {}, status: {}",
                            event.getEventId(),
                            event.getEventType(),
                            event.getRetryCount(),
                            event.getStatus(),
                            exception
                        );
                }
            }
        }

        log.debug("OutboxRelay 회차 완료: 총 {}건 선점, 성공 {}건, 실패 {}건", pendingEvents.size(), successCount, failureCount);
        return pendingEvents.size();
    }
}
