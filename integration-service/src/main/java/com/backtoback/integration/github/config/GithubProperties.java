package com.backtoback.integration.github.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * GitHub App 연동 설정 프로퍼티 (api-spec.md > 8.1.2, 8.1.3).
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "integration.github")
public class GithubProperties {

    /**
     * GitHub App ID.
     */
    private String appId;

    /**
     * GitHub App Slug (설치 URL 생성용 식별자, 예: resolve-sync).
     */
    private String appSlug = "resolve-sync";

    /**
     * GitHub App Private Key (PKCS#8 PEM 형식 또는 Base64 인코딩).
     */
    private String privateKey;

    /**
     * GitHub REST API Base URL.
     */
    private String apiBaseUrl = "https://api.github.com";

    /**
     * GitHub App 설치 state 유효 시간(초, 기본 10분 = 600초).
     */
    private long stateTtlSeconds = 600L;
}
