package com.backtoback.judge.judging.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.backtoback.common.event.config.EventConsumerConfiguration;
import com.backtoback.common.event.config.OutboxStorageConfiguration;

/** Judge DB에서 업무·소비 기록·결과 Outbox를 같은 트랜잭션으로 연결한다. */
@Configuration(proxyBeanMethods = false)
@Import(
    {
        EventConsumerConfiguration.class,
        OutboxStorageConfiguration.class
    }
)
public class JudgeEventStorageConfiguration {
    // 실제 AWS 통신은 JudgeMessagingConfiguration에서 명시적으로 활성화한다.
}
