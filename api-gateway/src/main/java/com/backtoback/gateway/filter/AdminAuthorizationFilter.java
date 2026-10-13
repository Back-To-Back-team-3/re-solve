package com.backtoback.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;

import com.backtoback.gateway.support.ErrorResponseWriter;

import reactor.core.publisher.Mono;

/**
 * /api/v1/admin/** 관리자 경로에 대해 X-User-Role이 ADMIN인지 검증하고 권한 부족 시 403으로 차단하는 필터.
 */
@Component
public class AdminAuthorizationFilter implements GlobalFilter, Ordered {

    private static final String ADMIN_PATH_PATTERN = "/api/v1/admin/**";
    private static final String ADMIN_ROLE = "ADMIN";

    private final AntPathMatcher pathMatcher = new AntPathMatcher();

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        if (pathMatcher.match(ADMIN_PATH_PATTERN, path)) {
            String role = exchange.getRequest().getHeaders().getFirst(RemoveExternalHeadersFilter.USER_ROLE_HEADER);
            if (!ADMIN_ROLE.equals(role)) {
                return ErrorResponseWriter
                    .write(exchange, HttpStatus.FORBIDDEN, "AUTH_ACCESS_DENIED", "관리자 권한이 필요합니다.");
            }
        }

        return chain.filter(exchange);
    }

    @Override
    public int getOrder() {
        return FilterOrder.ADMIN_AUTHORIZATION;
    }
}
