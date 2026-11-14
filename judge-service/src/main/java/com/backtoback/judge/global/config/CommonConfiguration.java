package com.backtoback.judge.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.common.web.logging.HttpClientConfiguration;

/**
 * 채점 서비스에 공통 오류 응답과 HTTP 요청 추적 구성을 등록한다.
 */
@Configuration(proxyBeanMethods = false)
@Import(
    {
        GlobalExceptionHandler.class,
        CorrelationIdFilter.class,
        HttpClientConfiguration.class
    }
)
public class CommonConfiguration {
    // 공통 Bean은 위의 Import 목록으로만 등록한다.
}
