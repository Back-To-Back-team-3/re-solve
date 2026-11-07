package com.backtoback.problem.problem.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;

/** Judge 진행 조회에 공통 HTTP 제한과 독립 서킷을 적용한다. */
@Configuration(proxyBeanMethods = false)
public class JudgeProgressConfiguration {
    /**
     * 추적 ID를 전달하는 기본 빌더에 Judge 주소와 2초 제한을 적용한다.
     *
     * @param builder 공통 추적 설정이 적용된 빌더
     * @param properties Judge 주소
     * @return 진행 조회용 클라이언트
     */
    @Bean
    public RestClient judgeProgressHttp(RestClient.Builder builder, ProblemIntegrationProperties properties) {
        var factory = new SimpleClientHttpRequestFactory();
        var timeout = Duration.ofSeconds(2);
        factory.setConnectTimeout(timeout);
        factory.setReadTimeout(timeout);
        return builder.clone().baseUrl(properties.judgeUrl().toString()).requestFactory(factory).build();
    }

    /**
     * Judge 조회 장애를 기록하고 회복 시 탐색 호출을 허용한다.
     *
     * @return 진행 조회에 공유하는 서킷
     */
    @Bean
    public CircuitBreaker judgeProgressCircuit() {
        return CircuitBreaker.ofDefaults("judgeValidationProgress");
    }
}
