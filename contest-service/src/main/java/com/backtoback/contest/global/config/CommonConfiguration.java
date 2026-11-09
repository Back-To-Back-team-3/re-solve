package com.backtoback.contest.global.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.backtoback.common.event.config.EventConsumerConfiguration;
import com.backtoback.common.event.config.OutboxStorageConfiguration;
import com.backtoback.common.jpa.config.JpaAuditingConfiguration;
import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.common.web.logging.HttpClientConfiguration;
import com.backtoback.contest.exam.client.ExamDependencyProperties;
import com.backtoback.contest.exam.client.ExamRunProperties;
import com.backtoback.contest.exam.client.MemberClientProperties;

/**
 * contest-service에서 사용할 공통 MVC, 요청 추적, HTTP 호출, JPA 감사와 이벤트 저장 구성을 등록한다.
 * API 계약은 API 명세서 v1.1 §0.1, §0.3과 도메인 및 데이터베이스 v1.0 §5를 따른다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(
    {
        MemberClientProperties.class,
        ExamDependencyProperties.class,
        ExamRunProperties.class
    }
)
@Import(
    {
        GlobalExceptionHandler.class,
        CorrelationIdFilter.class,
        HttpClientConfiguration.class,
        JpaAuditingConfiguration.class,
        OutboxStorageConfiguration.class,
        EventConsumerConfiguration.class
    }
)
public class CommonConfiguration {
    // 공통 설정은 명시적 import로만 등록한다.
}
