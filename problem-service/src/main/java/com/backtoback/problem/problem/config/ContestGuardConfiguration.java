package com.backtoback.problem.problem.config;

import java.time.Duration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import com.backtoback.problem.problem.client.ContestGuardClient;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import tools.jackson.databind.ObjectMapper;

/** Contest 기본 서비스 주소와 2초 HTTP 제한과 전용 서킷을 적용한다. */
@Configuration(proxyBeanMethods = false)
public class ContestGuardConfiguration {
    /**
     * 공통 추적 빌더를 사용해 자원 가드 클라이언트를 구성한다.
     *
     * @param builder 추적 설정 빌더
     * @param mapper 공통 JSON 변환기
     * @return 자원 가드 클라이언트
     */
    @Bean
    public ContestGuardClient contestGuardClient(RestClient.Builder builder, ObjectMapper mapper) {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(2));
        factory.setReadTimeout(Duration.ofSeconds(2));
        return new ContestGuardClient(
            builder.clone().baseUrl("http://contest-service:8080").requestFactory(factory).build(),
            mapper,
            CircuitBreaker.ofDefaults("contestProblemGuard")
        );
    }
}
