package com.backtoback.judge.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.backtoback.common.scheduling.config.OutboxCleanupConfiguration;
import com.backtoback.common.scheduling.config.ProcessedEventCleanupConfiguration;

/** Judge의 발행 완료·소비 완료 기록을 공통 보관기간과 DB 잠금으로 정리한다. */
@Configuration(proxyBeanMethods = false)
@Import(
    {
        OutboxCleanupConfiguration.class,
        ProcessedEventCleanupConfiguration.class
    }
)
public class JudgeEventCleanupConfiguration {
    // 메시지 수신을 중지한 동안에도 이미 완료된 기록의 보관 정책은 계속 적용한다.
}
