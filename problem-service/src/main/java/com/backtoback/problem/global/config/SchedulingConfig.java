package com.backtoback.problem.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.backtoback.common.scheduling.config.OutboxCleanupConfiguration;
import com.backtoback.common.scheduling.config.ProcessedEventCleanupConfiguration;

/** 공통 DB 잠금으로 Problem의 예약 작업과 이벤트 기록 정리를 실행한다. */
@Configuration(proxyBeanMethods = false)
@Import(
    {
        OutboxCleanupConfiguration.class,
        ProcessedEventCleanupConfiguration.class
    }
)
public class SchedulingConfig {
    // 기존 태그 정리와 공통 이벤트 정리가 같은 잠금 공급자를 사용한다.
}
