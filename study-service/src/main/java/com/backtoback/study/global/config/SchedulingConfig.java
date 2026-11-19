package com.backtoback.study.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;

import com.backtoback.common.scheduling.config.OutboxCleanupConfiguration;
import com.backtoback.common.scheduling.config.ProcessedEventCleanupConfiguration;

/**
 * 공통 DB 잠금으로 스터디 서비스의 예약 작업과 이벤트 기록 정리를 실행한다.
 * 발행 완료 후 7일, 소비 완료 후 14일이 지난 기록은 발행 설정과 무관하게 정리한다
 * (스케줄러 명세서 §0.2.2·§0.2.3, 공통 모듈 적용 가이드 §2.4).
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Import(
    {
        OutboxCleanupConfiguration.class,
        ProcessedEventCleanupConfiguration.class
    }
)
public class SchedulingConfig {
    // 공통 잠금 설정은 LockProvider가 하나일 때만 기동하므로 서비스 자체 공급자를 두지 않는다.
}
