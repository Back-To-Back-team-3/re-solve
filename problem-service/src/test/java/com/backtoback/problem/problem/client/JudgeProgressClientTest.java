package com.backtoback.problem.problem.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.config.JudgeProgressConfiguration;
import com.backtoback.problem.problem.config.ProblemIntegrationProperties;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

/** 실제 HTTP 응답 지연과 서킷 개방 중의 진행 조회를 검증한다. */
class JudgeProgressClientTest {
    /**
     * 응답하지 않는 로컬 서버로 읽기 제한을 확인하고 열린 서킷의 재호출을 거부한다.
     *
     * @throws IOException 로컬 소켓 생성 실패
     */
    @Test
    @DisplayName("Judge가 응답하지 않으면 2초에 실패하고 열린 서킷은 호출을 차단한다")
    void timeoutOpensCircuit() throws IOException {
        try (var socket = new ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))) {
            var properties
                = new ProblemIntegrationProperties(URI.create("http://127.0.0.1:" + socket.getLocalPort()), null);
            var http = new JudgeProgressConfiguration().judgeProgressHttp(RestClient.builder(), properties);
            var circuit = CircuitBreaker.of("judge", CircuitBreakerConfig.custom().minimumNumberOfCalls(1).build());
            var client = new JudgeProgressClient(http, JsonMapper.builder().build(), circuit);
            long start = System.nanoTime();
            assertTimeoutPreemptively(
                Duration.ofSeconds(5),
                () -> assertUnavailable(client, ResourceAccessException.class)
            );
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isGreaterThanOrEqualTo(Duration.ofMillis(1900));
            assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            assertUnavailable(client, CallNotPermittedException.class);
            assertThat(circuit.getMetrics().getNumberOfNotPermittedCalls()).isOne();
        }
    }

    /**
     * 업무상 작업 없음이 아닌 404의 원인과 오류 본문 파싱 실패를 보존한다.
     *
     * @param malformed 오류 본문이 잘못된 JSON인지 여부
     */
    @ParameterizedTest
    @ValueSource(
        booleans = {
            false,
            true
        }
    )
    @DisplayName("일반 404와 잘못된 오류 본문은 원인 예외를 보존한 503으로 전달한다")
    void preservesNotFoundCause(boolean malformed) {
        var builder = RestClient.builder().baseUrl("http://judge");
        var server = MockRestServiceServer.bindTo(builder).build();
        var client
            = new JudgeProgressClient(
                builder.build(),
                JsonMapper.builder().build(),
                CircuitBreaker.ofDefaults("notFound")
            );
        server
            .expect(requestTo("http://judge/internal/v1/judge/validations/1"))
            .andRespond(
                withStatus(HttpStatus.NOT_FOUND)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(malformed ? "{" : "{\"error\":{\"code\":\"OTHER_ERROR\"}}")
            );
        assertUnavailable(client, malformed ? JacksonException.class : HttpClientErrorException.NotFound.class);
        server.verify();
    }

    /**
     * 진행 조회가 공통 의존 장애로 처리되는지 확인한다.
     *
     * @param client Judge 조회 클라이언트
     * @param expectedCause 원래 오류 타입
     */
    private void assertUnavailable(JudgeProgressClient client, Class<? extends Throwable> expectedCause) {
        assertThatThrownBy(() -> client.read("1", "2", "java"))
            .isInstanceOfSatisfying(ProblemException.class, error -> {
                assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
                assertThat(error.getCause()).isInstanceOf(expectedCause);
            });
    }
}
