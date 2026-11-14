package com.backtoback.integration.github.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * judge-service 내부 API 연동 설정 프로퍼티 (api-spec.md &gt; 3.3.3).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "integration.judge")
public class JudgeProperties {

    /**
     * judge-service 내부 API Base URL (Compose 내부 네트워크 주소).
     */
    private String baseUrl = "http://judge-service:8080";
}
