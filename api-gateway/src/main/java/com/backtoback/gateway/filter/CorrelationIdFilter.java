package com.backtoback.gateway.filter;

import java.util.UUID;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * 전 구간 분산 추적을 위해 X-Correlation-Id를 검증 및 발급하고 요청/응답 헤더에 전파하는 필터.
 */
@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    public static final String CORRELATION_ID_HEADER = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String existingCorrelationId = exchange.getRequest().getHeaders().getFirst(CORRELATION_ID_HEADER);
        String correlationId
            = StringUtils.hasText(existingCorrelationId) ? existingCorrelationId : UUID.randomUUID().toString();

        ServerHttpRequest mutatedRequest
            = exchange.getRequest().mutate().header(CORRELATION_ID_HEADER, correlationId).build();

        exchange.getResponse().getHeaders().set(CORRELATION_ID_HEADER, correlationId);

        return chain.filter(exchange.mutate().request(mutatedRequest).build());
    }

    @Override
    public int getOrder() {
        return FilterOrder.CORRELATION_ID;
    }
}
