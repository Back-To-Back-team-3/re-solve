package com.backtoback.notification.event.scheduler;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.backtoback.notification.event.service.ProcessedEventCleanupService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * 매일 02:10(Asia/Seoul)에 보관 기간(14일)이 지난 소비 이벤트 멱등 기록({@code processed_events})을
 * 1,000건 배치 단위로 반복 정리하는 스케줄러 (스케줄러 명세서 0.1, 0.2.3).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ProcessedEventCleanupScheduler {

    public static final int BATCH_SIZE = ProcessedEventCleanupService.BATCH_SIZE;
    public static final Duration MAX_EXECUTION_DURATION = Duration.ofMinutes(10);
    public static final Duration SINGLE_BATCH_TIMEOUT
        = Duration.ofSeconds(ProcessedEventCleanupService.SINGLE_BATCH_TIMEOUT_SECONDS);
    public static final Duration SELECTION_WINDOW = MAX_EXECUTION_DURATION.minus(SINGLE_BATCH_TIMEOUT);

    private final ProcessedEventCleanupService cleanupService;
    private final Clock clock;

    /**
     * 매일 02:10에 14일 경과한 {@code processed_events} 기록을 1,000건 단위로 반복 삭제한다 (스케줄러 명세서 0.2.3).
     */
    @Scheduled(
        cron = "${notification.event.cleanup-cron:0 10 2 * * *}",
        zone = "Asia/Seoul"
    )
    @SchedulerLock(
        name = "ProcessedEventCleanupScheduler",
        lockAtMostFor = "PT30M"
    )
    public void cleanupExpiredProcessedEvents() {
        Instant selectionDeadline = clock.instant().plus(SELECTION_WINDOW);
        int deletedEvents = 0;

        try {
            while (clock.instant().isBefore(selectionDeadline)) {
                int deleted = cleanupService.deleteExpiredProcessedEventsBatch(BATCH_SIZE);
                deletedEvents += deleted;
                if (deleted < BATCH_SIZE) {
                    break;
                }
            }
        } catch (Exception exception) {
            log.warn("만료 소비 이벤트 기록(14일 경과) 배치 삭제 중 예외 발생: deletedEvents={}", deletedEvents, exception);
        }

        if (deletedEvents > 0) {
            log.info("소비 이벤트 멱등 기록 정리 배치 완료: deletedEvents={}", deletedEvents);
        }
    }
}
