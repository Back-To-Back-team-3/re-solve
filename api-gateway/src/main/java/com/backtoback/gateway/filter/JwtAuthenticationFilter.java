package com.backtoback.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.server.ServerWebExchange;

import com.backtoback.gateway.auth.JwtTokenValidator;
import com.backtoback.gateway.auth.WhiteListMatcher;
import com.backtoback.gateway.config.AuthProperties;
import com.backtoback.gateway.support.ErrorResponseWriter;

import reactor.core.publisher.Mono;

/**
 * Bearer JWT를 검증하고, 추출된 사용자 식별 정보(X-User-Id, X-User-Role)를 다운스트림 요청에 주입하는 필터.
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTokenValidator tokenValidator;
    private final WhiteListMatcher whiteListMatcher;
    private final AuthProperties authProperties;

    public JwtAuthenticationFilter(
        JwtTokenValidator tokenValidator,
        WhiteListMatcher whiteListMatcher,
        AuthProperties authProperties
    ) {
        this.tokenValidator = tokenValidator;
        this.whiteListMatcher = whiteListMatcher;
        this.authProperties = authProperties;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        // 1. 내부 API(/internal/**) 외부 접근 차단
        if (whiteListMatcher.isInternalPath(path)) {
            return ErrorResponseWriter
                .write(exchange, HttpStatus.FORBIDDEN, "AUTH_ACCESS_DENIED", "내부 API는 외부에서 접근할 수 없습니다.");
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        boolean hasBearerToken = StringUtils.hasText(authHeader) && authHeader.startsWith(BEARER_PREFIX);

        // 2. 토큰이 없는 경우 처리
        if (!hasBearerToken) {
            if (whiteListMatcher.isWhiteList(exchange.getRequest())) {
                return chain.filter(exchange);
            }

            // 개발용 인증 우회 허용 확인
            boolean hasBypassUser
                = exchange.getRequest().getHeaders().getFirst(RemoveExternalHeadersFilter.USER_ID_HEADER) != null;
            if (authProperties.isBypassEnabled() && hasBypassUser) {
                ServerHttpRequest mutatedRequest
                    = exchange
                        .getRequest()
                        .mutate()
                        .header(RemoveExternalHeadersFilter.USER_ROLE_HEADER, "USER")
                        .build();
                return chain.filter(exchange.mutate().request(mutatedRequest).build());
            }

            return ErrorResponseWriter.write(exchange, HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_INVALID", "인증 토큰이 필요합니다.");
        }

        // 3. 토큰이 있는 경우 검증
        String token = authHeader.substring(BEARER_PREFIX.length()).trim();
        return tokenValidator.validate(token).flatMap(principal -> {
            ServerHttpRequest mutatedRequest
                = exchange
                    .getRequest()
                    .mutate()
                    .header(RemoveExternalHeadersFilter.USER_ID_HEADER, principal.memberId())
                    .header(RemoveExternalHeadersFilter.USER_ROLE_HEADER, principal.role())
                    .build();
            return chain.filter(exchange.mutate().request(mutatedRequest).build());
        })
            .onErrorResume(
                error -> ErrorResponseWriter
                    .write(exchange, HttpStatus.UNAUTHORIZED, "AUTH_TOKEN_INVALID", "인증 정보가 유효하지 않습니다.")
            );
    }

    @Override
    public int getOrder() {
        return FilterOrder.JWT_AUTHENTICATION;
    }
}
