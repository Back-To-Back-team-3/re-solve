package com.backtoback.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;

class AdminAuthorizationFilterTest {

    private final AdminAuthorizationFilter filter = new AdminAuthorizationFilter();

    @Test
    @DisplayName("일반 USER 권한으로 관리자 경로에 접근하면 403 Forbidden을 반환한다")
    void blocksUserOnAdminPath() {
        MockServerHttpRequest request
            = MockServerHttpRequest
                .get("/api/v1/admin/members")
                .header(RemoveExternalHeadersFilter.USER_ROLE_HEADER, "USER")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean chainCalled = new AtomicBoolean(false);
        GatewayFilterChain chain = mutatedExchange -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(chainCalled.get()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("ADMIN 권한으로 관리자 경로에 접근하면 다운스트림으로 전달된다")
    void allowsAdminOnAdminPath() {
        MockServerHttpRequest request
            = MockServerHttpRequest
                .get("/api/v1/admin/members")
                .header(RemoveExternalHeadersFilter.USER_ROLE_HEADER, "ADMIN")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean chainCalled = new AtomicBoolean(false);
        GatewayFilterChain chain = mutatedExchange -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(chainCalled.get()).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }

    @Test
    @DisplayName("관리자 경로가 아닌 일반 경로는 ADMIN 권한이 없어도 통과한다")
    void allowsUserOnNonAdminPath() {
        MockServerHttpRequest request
            = MockServerHttpRequest
                .get("/api/v1/problems")
                .header(RemoveExternalHeadersFilter.USER_ROLE_HEADER, "USER")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean chainCalled = new AtomicBoolean(false);
        GatewayFilterChain chain = mutatedExchange -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(chainCalled.get()).isTrue();
    }
}
