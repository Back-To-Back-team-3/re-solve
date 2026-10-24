package com.backtoback.judge.judging.config;

import java.net.http.HttpClient;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import lombok.RequiredArgsConstructor;

/**
 * 검증한 통신 설정으로 Judge0 전용 HTTP 클라이언트를 등록한다.
 * 공통 요청 추적을 유지하며 코드 실행 요청과 결과 처리는 담당하지 않는다.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(Judge0Properties.class)
@RequiredArgsConstructor
public class Judge0Configuration {
    private final Judge0Properties properties;

    /**
     * Judge0 전용 연결·응답 제한과 인증 헤더를 적용한 HTTP 클라이언트를 생성한다.
     * 시작 시 외부 서버에 연결하지 않으며 공통 요청 추적 설정은 유지한다.
     *
     * @param builder 공통 RestClient 설정이 적용된 빌더
     * @return 실행 생성·조회 전용 클라이언트
     */
    @Bean
    public RestClient judge0RestClient(RestClient.Builder builder) {
        HttpClient httpClient
            = HttpClient
                .newBuilder()
                .connectTimeout(properties.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.readTimeout());
        builder = builder.clone().requestFactory(factory);
        if (properties.baseUrl() != null && !properties.baseUrl().isBlank()) {
            builder.baseUrl(properties.baseUrl());
        }
        if (properties.authToken() != null && !properties.authToken().isBlank()) {
            builder.defaultHeader(properties.authHeader(), properties.authToken());
        }
        return builder.build();
    }
}
