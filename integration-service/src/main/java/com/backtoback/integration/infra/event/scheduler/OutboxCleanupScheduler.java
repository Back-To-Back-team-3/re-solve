package com.backtoback.integration.infra.event.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.backtoback.integration.infra.event.service.EventCleanupService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 매일 02:00(Asia/Seoul)에 발행 완료 후 7일이 지난 Outbox 이벤트를 1,000건 단위로 정리하는 스케줄러 (scheduler.md > 0.2.2).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxCleanupScheduler {

    public static final int BATCH_SIZE = EventCleanupService.BATCH_SIZE;
    public static final Duration MAX_EXECUTION_DURATION = Duration.ofMinutes(10);

    private final EventCleanupService eventCleanupService;
    private final Clock clock;

    /**
     * 매일 02:00에 7일 경과한 PUBLISHED 상태의 Outbox 이벤트를 배치 단위로 반복 삭제한다.
     */
    @Scheduled(
        cron = "${infra.scheduler.outbox-cleanup-cron:0 0 2 * * *}",
        zone = "Asia/Seoul"
    )
    @SchedulerLock(
        name = "OutboxCleanupScheduler",
        lockAtMostFor = "PT30M"
    )
    public void cleanupExpiredPublishedEvents() {
        Instant deadline = clock.instant().plus(MAX_EXECUTION_DURATION);
        int totalDeleted = 0;

        try {
            while (clock.instant().isBefore(deadline)) {
                int deleted = eventCleanupService.deleteExpiredPublishedEventsBatch(BATCH_SIZE);
                totalDeleted += deleted;
                if (deleted < BATCH_SIZE) {
                    break;
                }
            }
            if (totalDeleted > 0) {
                log.info("OutboxCleanupScheduler 실행 완료: 총 {}건 삭제", totalDeleted);
            }
        } catch (Exception exception) {
            log.error("OutboxCleanupScheduler 실행 중 오류 발생 (삭제 완료 건수: {})", totalDeleted, exception);
        }
    }
}
