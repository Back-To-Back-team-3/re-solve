package com.backtoback.common.scheduling.scheduler;

import org.springframework.scheduling.annotation.Scheduled;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;

import com.backtoback.common.scheduling.service.EventRecordCleanupService;

/**
 * 발행 완료 후 7일이 지난 Outbox를 서울 시간 매일 02시에 정리한다.
 * <p>기준 문서: 스케줄러 v1.0 / §0.2.2 OutboxCleanupScheduler — 발행 완료 이벤트 정리</p>
 */
public class OutboxCleanupScheduler {
    private final EventRecordCleanupService cleanupService;

    /**
     * 잠금 획득 후 실행할 공통 배치 정리 처리를 연결한다.
     *
     * @param cleanupService 보관기간·배치·트랜잭션을 적용할 정리 실행기
     */
    public OutboxCleanupScheduler(EventRecordCleanupService cleanupService) {
        this.cleanupService = cleanupService;
    }

    /**
     * 같은 서비스의 다른 인스턴스와 중복 실행하지 않고 발행 완료 기록만 정리한다.
     */
    @Scheduled(
        cron = "0 0 2 * * *",
        zone = "Asia/Seoul"
    )
    @SchedulerLock(
        name = "OutboxCleanupScheduler",
        lockAtMostFor = "PT30M"
    )
    public void cleanup() {
        cleanupService.cleanPublishedEvents();
    }
}
