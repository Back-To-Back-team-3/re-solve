package com.backtoback.gateway.filter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.security.oauth2.jwt.JwtException;

import com.backtoback.gateway.auth.JwtTokenValidator;
import com.backtoback.gateway.auth.UserPrincipal;
import com.backtoback.gateway.auth.WhiteListMatcher;
import com.backtoback.gateway.config.AuthProperties;

import reactor.core.publisher.Mono;

@ExtendWith(MockitoExtension.class)
class JwtAuthenticationFilterTest {

    @Mock
    private JwtTokenValidator tokenValidator;

    private WhiteListMatcher whiteListMatcher;
    private AuthProperties properties;
    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        whiteListMatcher = new WhiteListMatcher();
        properties = new AuthProperties(Mockito.mock(Environment.class));
        properties.setBypassEnabled(false);
        filter = new JwtAuthenticationFilter(tokenValidator, whiteListMatcher, properties);
    }

    @Test
    @DisplayName("내부 API 경로는 외부 접근 시 즉시 403 Forbidden으로 차단된다")
    void blocksInternalPath() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/internal/v1/members").build();
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
    @DisplayName("공개 경로는 토큰이 없어도 정상 통과한다")
    void allowsWhiteListWithoutToken() {
        MockServerHttpRequest request = MockServerHttpRequest.get("/api/v1/problems").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean chainCalled = new AtomicBoolean(false);
        GatewayFilterChain chain = mutatedExchange -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(chainCalled.get()).isTrue();
    }

    @Test
    @DisplayName("보호된 경로에 토큰이 없으면 401 Unauthorized를 반환한다")
    void blocksProtectedPathWithoutToken() {
        MockServerHttpRequest request = MockServerHttpRequest.post("/api/v1/problems").build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean chainCalled = new AtomicBoolean(false);
        GatewayFilterChain chain = mutatedExchange -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(chainCalled.get()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("유효한 Bearer 토큰이 전달되면 X-User-Id와 X-User-Role을 주입하여 통과한다")
    void injectsUserHeadersOnValidToken() {
        given(tokenValidator.validate("valid-token")).willReturn(Mono.just(new UserPrincipal("7", "ADMIN")));

        MockServerHttpRequest request
            = MockServerHttpRequest
                .post("/api/v1/problems")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicReference<HttpHeaders> downstreamHeaders = new AtomicReference<>();
        GatewayFilterChain chain = mutatedExchange -> {
            downstreamHeaders.set(mutatedExchange.getRequest().getHeaders());
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(downstreamHeaders.get()).isNotNull();
        assertThat(downstreamHeaders.get().getFirst(RemoveExternalHeadersFilter.USER_ID_HEADER)).isEqualTo("7");
        assertThat(downstreamHeaders.get().getFirst(RemoveExternalHeadersFilter.USER_ROLE_HEADER)).isEqualTo("ADMIN");
    }

    @Test
    @DisplayName("유효하지 않은 토큰이면 401 Unauthorized를 반환한다")
    void returns401OnInvalidToken() {
        given(tokenValidator.validate(anyString())).willReturn(Mono.error(new JwtException("Invalid signature")));

        MockServerHttpRequest request
            = MockServerHttpRequest
                .post("/api/v1/problems")
                .header(HttpHeaders.AUTHORIZATION, "Bearer invalid-token")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        AtomicBoolean chainCalled = new AtomicBoolean(false);
        GatewayFilterChain chain = mutatedExchange -> {
            chainCalled.set(true);
            return Mono.empty();
        };

        filter.filter(exchange, chain).block();

        assertThat(chainCalled.get()).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("다운스트림 서비스의 오류는 401로 변환되지 않고 그대로 전파된다")
    void propagatesDownstreamErrorWithoutConvertingTo401() {
        given(tokenValidator.validate("valid-token")).willReturn(Mono.just(new UserPrincipal("7", "USER")));

        MockServerHttpRequest request
            = MockServerHttpRequest
                .post("/api/v1/problems")
                .header(HttpHeaders.AUTHORIZATION, "Bearer valid-token")
                .build();
        MockServerWebExchange exchange = MockServerWebExchange.from(request);

        GatewayFilterChain chain = mutatedExchange -> Mono.error(new IllegalStateException("Downstream failure"));

        assertThatThrownBy(() -> filter.filter(exchange, chain).block())
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Downstream failure");

        assertThat(exchange.getResponse().getStatusCode()).isNull();
    }
}
