package com.backtoback.problem.global.logging;

import org.slf4j.MDC;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 이 클래스의 @Bean 메서드 호출을 중개하는 프록시를 만들지 않는 설정
@Configuration(proxyBeanMethods = false)
public class HttpClientConfiguration {

    /**
     * 현재 요청의 추적 ID를 외부 HTTP 호출 헤더로 전달하도록 설정한다.
     *
     * @return MDC의 추적 ID를 전달하는 RestClient 설정 함수
     */
    @Bean
    RestClientCustomizer correlationIdCustomizer() {
        return builder -> builder.requestInterceptor((request, body, execution) -> {
            // 현재 스레드의 MDC에서 correlationId를 가져와 값이 있으면 나가는 요청의 X-Correlation-Id 헤더에 넣음.
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (correlationId != null) {
                request.getHeaders().set(CorrelationIdFilter.HEADER, correlationId);
            }
            return execution.execute(request, body);
        });
    }
}
