package com.backtoback.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;

class CorrelationIdFilterTest {

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    @DisplayName("요청에 X-Correlation-Id가 없으면 신규 UUID를 발급해 요청 및 응답에 설정한다")
    void generatesCorrelationIdWhenMissing() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/problems").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<String> downstreamCorrelationId = new AtomicReference<>();
        GatewayFilterChain chain = mutatedExchange -> {
            downstreamCorrelationId
                .set(mutatedExchange.getRequest().getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(downstreamCorrelationId.get()).isNotBlank();
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER))
            .isEqualTo(downstreamCorrelationId.get());
    }

    @Test
    @DisplayName("요청에 이미 X-Correlation-Id가 있으면 해당 값을 유지한다")
    void preservesExistingCorrelationId() {
        String existingId = "client-trace-12345";
        MockServerHttpRequest request
            = MockServerHttpRequest
                .get("/api/v1/problems")
                .header(CorrelationIdFilter.CORRELATION_ID_HEADER, existingId)
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<String> downstreamCorrelationId = new AtomicReference<>();
        GatewayFilterChain chain = mutatedExchange -> {
            downstreamCorrelationId
                .set(mutatedExchange.getRequest().getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER));
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(downstreamCorrelationId.get()).isEqualTo(existingId);
        assertThat(exchange.getResponse().getHeaders().getFirst(CorrelationIdFilter.CORRELATION_ID_HEADER))
            .isEqualTo(existingId);
    }
}
