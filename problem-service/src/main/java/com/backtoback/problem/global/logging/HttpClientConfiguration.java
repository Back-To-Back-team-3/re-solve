package com.backtoback.problem.global.logging;

import org.slf4j.MDC;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

// 이 클래스의 @Bean 메서드 호출을 중개하는 프록시를 만들지 않는 설정
@Configuration(proxyBeanMethods = false)
public class HttpClientConfiguration {

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
