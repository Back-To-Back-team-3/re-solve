package com.backtoback.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import com.backtoback.gateway.config.AuthProperties;

import reactor.core.publisher.Mono;

class RemoveExternalHeadersFilterTest {

    @Test
    @DisplayName("기본 상태(우회 비활성화)에서는 외부에서 전달된 X-User-Id 및 X-User-Role이 제거된다")
    void removesExternalUserHeadersByDefault() {
        AuthProperties properties = new AuthProperties(Mockito.mock(Environment.class));
        properties.setBypassEnabled(false);
        RemoveExternalHeadersFilter filter = new RemoveExternalHeadersFilter(properties);

        MockServerHttpRequest request
            = MockServerHttpRequest
                .get("/api/v1/problems")
                .header(RemoveExternalHeadersFilter.USER_ID_HEADER, "100")
                .header(RemoveExternalHeadersFilter.USER_ROLE_HEADER, "ADMIN")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<HttpHeaders> downstreamHeaders = new AtomicReference<>();
        GatewayFilterChain chain = mutatedExchange -> {
            downstreamHeaders.set(mutatedExchange.getRequest().getHeaders());
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(downstreamHeaders.get().getFirst(RemoveExternalHeadersFilter.USER_ID_HEADER)).isNull();
        assertThat(downstreamHeaders.get().getFirst(RemoveExternalHeadersFilter.USER_ROLE_HEADER)).isNull();
    }

    @Test
    @DisplayName("인증 우회가 활성화된 경우 X-User-Id는 유지되고 X-User-Role만 제거된다")
    void keepsUserIdWhenBypassEnabled() {
        AuthProperties properties = new AuthProperties(Mockito.mock(Environment.class));
        properties.setBypassEnabled(true);
        RemoveExternalHeadersFilter filter = new RemoveExternalHeadersFilter(properties);

        MockServerHttpRequest request
            = MockServerHttpRequest
                .get("/api/v1/problems")
                .header(RemoveExternalHeadersFilter.USER_ID_HEADER, "100")
                .header(RemoveExternalHeadersFilter.USER_ROLE_HEADER, "ADMIN")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<HttpHeaders> downstreamHeaders = new AtomicReference<>();
        GatewayFilterChain chain = mutatedExchange -> {
            downstreamHeaders.set(mutatedExchange.getRequest().getHeaders());
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(downstreamHeaders.get().getFirst(RemoveExternalHeadersFilter.USER_ID_HEADER)).isEqualTo("100");
        assertThat(downstreamHeaders.get().getFirst(RemoveExternalHeadersFilter.USER_ROLE_HEADER)).isNull();
    }
}
