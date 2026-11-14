package com.backtoback.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import com.backtoback.gateway.config.AuthProperties;

import reactor.core.publisher.Mono;

/**
 * 외부 요청에 포함된 사용자 식별 헤더(X-User-Id, X-User-Role) 위조를 방어하기 위해 진입 시점에 제거하는 필터.
 */
@Component
public class RemoveExternalHeadersFilter implements GlobalFilter, Ordered {

    public static final String USER_ID_HEADER = "X-User-Id";
    public static final String USER_ROLE_HEADER = "X-User-Role";

    private final AuthProperties authProperties;

    public RemoveExternalHeadersFilter(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest.Builder requestBuilder = exchange.getRequest().mutate();

        if (authProperties.isBypassEnabled()) {
            requestBuilder.headers(httpHeaders -> httpHeaders.remove(USER_ROLE_HEADER));
        } else {
            requestBuilder.headers(httpHeaders -> {
                httpHeaders.remove(USER_ID_HEADER);
                httpHeaders.remove(USER_ROLE_HEADER);
            });
        }

        return chain.filter(exchange.mutate().request(requestBuilder.build()).build());
    }

    @Override
    public int getOrder() {
        return FilterOrder.REMOVE_EXTERNAL_HEADERS;
    }
}
