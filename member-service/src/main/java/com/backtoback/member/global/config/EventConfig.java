package com.backtoback.member.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.backtoback.common.event.config.OutboxStorageConfiguration;

/**
 * 업무 트랜잭션 안에서 Outbox에 이벤트를 기록하는 공통 설정을 등록한다(공통 모듈 적용 가이드 2.4).
 * SNS 발행은 {@link MessagingConfig}가 켜진 환경에서만 등록한다.
 */
@Configuration(proxyBeanMethods = false)
@Import(OutboxStorageConfiguration.class)
public class EventConfig {

}
