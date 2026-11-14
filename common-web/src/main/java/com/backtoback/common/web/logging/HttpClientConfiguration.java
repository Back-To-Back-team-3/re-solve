package com.backtoback.common.web.logging;

import org.slf4j.MDC;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring Boot가 제공하는 RestClient.Builder에 현재 스레드의 추적 ID 전달 처리를 추가한다.
 * 사용하는 서비스에서 컴포넌트 스캔 또는 명시적 import로 등록해야 한다.
 * 직접 생성한 RestClient.Builder에는 자동 적용되지 않으며 customizer를 직접 연결해야 한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>API 명세서 v1.1 / §0.1 기본 규칙: HTTP 호출 간 X-Correlation-Id 전파</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
public class HttpClientConfiguration {
    /**
     * HTTP 요청을 실행할 때 MDC의 추적 ID를 읽어 요청 헤더에 설정한다.
     * 현재 추적 ID가 없으면 호출자가 지정한 헤더를 변경하지 않는다.
     *
     * @return 현재 추적 ID를 전달하는 RestClient 설정 함수
     */
    @Bean
    RestClientCustomizer correlationIdCustomizer() {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (correlationId != null) {
                request.getHeaders().set(CorrelationIdFilter.HEADER, correlationId);
            }
            return execution.execute(request, body);
        });
    }
}
