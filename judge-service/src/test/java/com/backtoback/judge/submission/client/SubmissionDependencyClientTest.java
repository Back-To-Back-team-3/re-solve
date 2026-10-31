package com.backtoback.judge.submission.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.MDC;
import org.springframework.boot.restclient.RestClientCustomizer;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.client.RestClient;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.web.logging.HttpClientConfiguration;
import com.backtoback.judge.global.exception.custom.CommonDependencyUnavailableException;
import com.backtoback.judge.global.exception.custom.ProblemNotAvailableException;
import com.backtoback.judge.submission.config.SubmissionClientProperties;
import com.backtoback.judge.submission.dto.ProblemJudgingDataResponse;
import com.sun.net.httpserver.HttpServer;

/** 실제 HTTP 직렬화·추적 전파·응답 장애의 fail-closed 동작을 확인한다. */
class SubmissionDependencyClientTest {
    private HttpServer server;
    private SubmissionDependencyClient client;
    private String body;
    private int status;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> correlation = new AtomicReference<>();

    /**
     * 외부 서비스 대신 로컬 HTTP 서버를 준비한다.
     *
     * @throws Exception 서버 생성 실패
     */
    @BeforeEach
    void prepare() throws Exception {
        body = "{\"memberId\":\"7\",\"status\":\"ACTIVE\",\"role\":\"USER\"}";
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            correlation.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            byte[] response = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, response.length);
            try (var output = exchange.getResponseBody()) {
                output.write(response);
            }
        });
        server.start();
        URI uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        var builder = RestClient.builder();
        try (var context = new AnnotationConfigApplicationContext(HttpClientConfiguration.class)) {
            context.getBean(RestClientCustomizer.class).customize(builder);
        }
        client
            = new SubmissionDependencyClient(builder, new SubmissionClientProperties(uri, uri, Duration.ofSeconds(2)));
    }

    /** 서버와 스레드의 상관관계 정보를 정리한다. */
    @AfterEach
    void close() {
        server.stop(0);
        MDC.clear();
    }

    /** 공통 추적과 내부 API의 데이터 객체를 처리한다. */
    @Test
    @DisplayName("내부 직접 객체 응답을 읽고 현재 Correlation ID를 전달한다")
    void readsDirectResponse() {
        MDC.put("correlationId", "submission-trace");
        assertThat(client.status(7).status()).isEqualTo("ACTIVE");
        assertThat(path.get()).isEqualTo("/internal/v1/members/7/status");
        assertThat(correlation.get()).isEqualTo("submission-trace");
    }

    /** 테스트 메타데이터만 역직렬화하고 외부 파일에는 접근하지 않는다. */
    @Test
    @DisplayName("고정 자료 응답에서 테스트 순번과 공개 여부만 읽고 S3 참조는 무시한다")
    void readsMetadataOnly() {
        body = """
            {"problemId":"301","problemRevisionId":"1001","supportedLanguages":[],"executionProfiles":[],
             "cases":[{"testAssetId":"501","testOrder":1,"isSample":false,"asset":{"objectKey":"private"}}],
             "functionSpec":{"name":"solution"},"executionLimits":{"timeMs":2000}}
            """;
        var data = client.data(301, 1001);
        assertThat(data.cases()).containsExactly(new ProblemJudgingDataResponse.TestCase("501", 1, false));
        assertThat(path.get()).isEqualTo("/internal/v1/problems/301/revisions/1001/judging/data");
    }

    /** Problem 미존재와 다른 서비스 장애를 구분한다. */
    @Test
    @DisplayName("Problem 404는 제출 불가이고 Member 404는 검증 의존성 실패다")
    void mapsNotFound() {
        status = 404;
        body = "{\"sensitive\":\"not-public\"}";
        assertCode(() -> client.validation(301, 1001), ProblemNotAvailableException.class, "PROBLEM_NOT_AVAILABLE");
        assertCode(() -> client.status(7), CommonDependencyUnavailableException.class, "COMMON_DEPENDENCY_UNAVAILABLE");
    }

    /**
     * HTTP 장애를 검증 성공으로 처리하지 않는다.
     *
     * @param responseStatus 외부 오류 HTTP 상태
     */
    @ParameterizedTest
    @ValueSource(
        ints = {
            429,
            500,
            503
        }
    )
    @DisplayName("외부 HTTP 오류는 본문을 노출하지 않고 503 검증 실패로 변환한다")
    void mapsHttpFailure(int responseStatus) {
        status = responseStatus;
        body = "{\"sensitive\":\"not-public\"}";
        assertCode(() -> client.levels(7), CommonDependencyUnavailableException.class, "COMMON_DEPENDENCY_UNAVAILABLE");
    }

    /**
     * 빈 응답과 변환 실패는 정상 데이터로 사용하지 않는다.
     *
     * @param responseBody 잘못된 내부 응답
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "",
            "not-json",
            "null"
        }
    )
    @DisplayName("비어 있거나 잘못된 JSON 응답은 fail-closed로 처리한다")
    void rejectsMalformedBody(String responseBody) {
        body = responseBody;
        assertCode(() -> client.status(7), CommonDependencyUnavailableException.class, "COMMON_DEPENDENCY_UNAVAILABLE");
    }

    /**
     * 공개 오류 코드만 전달되는지 확인한다.
     *
     * @param action 실패할 클라이언트 호출
     * @param type 기대 전용 예외 타입
     * @param code 기대 오류 코드
     */
    private void assertCode(Runnable action, Class<? extends BusinessException> type, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(type, exception -> {
            assertThat(exception.getErrorCode().getCode()).isEqualTo(code);
            assertThat(exception.getMessage()).doesNotContain("not-public");
        });
    }
}
