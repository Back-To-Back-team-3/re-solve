package com.backtoback.common.scheduling.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.backtoback.common.scheduling.scheduler.OutboxCleanupScheduler;
import com.backtoback.common.scheduling.service.EventRecordCleanupService;

/**
 * Outbox를 사용하는 서비스에서 명시적으로 등록할 발행 완료 기록 정리 설정이다.
 * 소비 기록 정리와 Relay는 활성화하지 않는다.
 * <p>기준 문서: 스케줄러 v1.0 / §0.2.2 OutboxCleanupScheduler — 발행 완료 이벤트 정리</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Import(
    {
        SchedulerLockConfiguration.class,
        EventCleanupStorageConfiguration.class
    }
)
public class OutboxCleanupConfiguration {
    /**
     * 서비스가 가져온 설정에 발행 완료 기록의 정리 작업만 연결한다.
     *
     * @param cleanupService 공통 보관기간과 배치 경계를 적용할 실행기
     * @return 서울 시간 매일 02시에 실행할 Outbox 정리 작업
     */
    @Bean
    public OutboxCleanupScheduler outboxCleanupScheduler(EventRecordCleanupService cleanupService) {
        return new OutboxCleanupScheduler(cleanupService);
    }
}
