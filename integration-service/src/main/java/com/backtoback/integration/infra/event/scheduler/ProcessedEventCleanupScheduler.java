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
 * 매일 02:10(Asia/Seoul)에 소비 완료 후 14일이 지난 소비 기록(processed_events)을 1,000건 단위로 정리하는 스케줄러 (scheduler.md > 0.2.3).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessedEventCleanupScheduler {

    public static final int BATCH_SIZE = EventCleanupService.BATCH_SIZE;
    public static final Duration MAX_EXECUTION_DURATION = Duration.ofMinutes(10);

    private final EventCleanupService eventCleanupService;
    private final Clock clock;

    /**
     * 매일 02:10에 14일 경과한 소비 완료 이벤트 기록을 배치 단위로 반복 삭제한다.
     */
    @Scheduled(
        cron = "${infra.scheduler.processed-cleanup-cron:0 10 2 * * *}",
        zone = "Asia/Seoul"
    )
    @SchedulerLock(
        name = "ProcessedEventCleanupScheduler",
        lockAtMostFor = "PT30M"
    )
    public void cleanupExpiredProcessedEvents() {
        Instant deadline = clock.instant().plus(MAX_EXECUTION_DURATION);
        int totalDeleted = 0;

        try {
            while (clock.instant().isBefore(deadline)) {
                int deleted = eventCleanupService.deleteExpiredProcessedEventsBatch(BATCH_SIZE);
                totalDeleted += deleted;
                if (deleted < BATCH_SIZE) {
                    break;
                }
            }
            if (totalDeleted > 0) {
                log.info("ProcessedEventCleanupScheduler 실행 완료: 총 {}건 삭제", totalDeleted);
            }
        } catch (Exception exception) {
            log.error("ProcessedEventCleanupScheduler 실행 중 오류 발생 (삭제 완료 건수: {})", totalDeleted, exception);
        }
    }
}
