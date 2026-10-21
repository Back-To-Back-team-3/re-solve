package com.backtoback.contest.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.backtoback.common.jpa.config.JpaAuditingConfiguration;
import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.common.web.logging.HttpClientConfiguration;

/**
 * contest-service에서 사용할 공통 MVC, 요청 추적, HTTP 호출, JPA 감사 구성을 등록한다.
 * API 계약은 API 명세서 v1.1 §0.1, §0.3과 도메인 및 데이터베이스 v1.0 §5를 따른다.
 */
@Configuration(proxyBeanMethods = false)
@Import(
    {
        GlobalExceptionHandler.class,
        CorrelationIdFilter.class,
        HttpClientConfiguration.class,
        JpaAuditingConfiguration.class
    }
)
public class CommonConfiguration {

}
