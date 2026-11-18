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

import com.backtoback.common.web.logging.HttpClientConfiguration;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.submission.config.SubmissionClientProperties;
import com.backtoback.judge.submission.dto.ProblemJudgingDataResponse;
import com.sun.net.httpserver.HttpServer;

import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import jakarta.validation.Validation;

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
            = new SubmissionDependencyClient(
                builder,
                new SubmissionClientProperties(uri, uri, uri, Duration.ofSeconds(2))
            );
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

    /** 동일한 회원과 문제의 실제 불리언 배정 응답을 확인한다. */
    @Test
    @DisplayName("Study가 동일 회원과 문제에 배정을 확인하면 예외를 허용한다")
    void readsAssignment() {
        body = """
            {"memberId":"7","problemId":"301","assigned":true}
            """;
        assertThat(client.isAssignedProblem(7, 301)).isTrue();
        assertThat(path.get()).isEqualTo("/internal/v1/studies/members/7/assigned-problems/301");
    }

    /**
     * 일치하지 않는 식별자와 잘못된 배정 플래그는 예외를 허용하지 않는다.
     *
     * @param responseBody 배정이 확인되지 않는 Study 응답
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "{\"memberId\":\"7\",\"problemId\":\"301\",\"assigned\":false}",
            "{\"memberId\":\"8\",\"problemId\":\"301\",\"assigned\":true}",
            "{\"memberId\":\"7\",\"problemId\":\"302\",\"assigned\":true}",
            "{\"memberId\":\"7\",\"problemId\":\"301\",\"assigned\":\"true\"}",
            "{\"memberId\":\"7\",\"problemId\":\"301\"}",
            "not-json"
        }
    )
    @DisplayName("미배정·식별자 불일치·응답 계약 오류에서는 잠금을 유지한다")
    void rejectsUnconfirmedAssignment(String responseBody) {
        body = responseBody;
        assertThat(client.isAssignedProblem(7, 301)).isFalse();
    }

    /**
     * 성공 상태가 200이 아닌 응답에서는 잠금을 유지한다.
     *
     * @param responseStatus Study의 HTTP 상태
     */
    @ParameterizedTest
    @ValueSource(
        ints = {
            302,
            404,
            503
        }
    )
    @DisplayName("Study의 리다이렉트나 오류 응답으로 잠금이 해제되지 않는다")
    void rejectsAssignmentHttpFailure(int responseStatus) {
        status = responseStatus;
        body = """
            {"memberId":"7","problemId":"301","assigned":true}
            """;
        assertThat(client.isAssignedProblem(7, 301)).isFalse();
    }

    /** 정상 미배정은 허용하고 연속 장애가 발생하면 Study 호출을 차단한다. */
    @Test
    @DisplayName("Study 미배정은 장애로 집계하지 않고 연속 실패 뒤에는 호출을 차단한다")
    void opensCircuitOnAssignmentFailures() {
        var config = CircuitBreakerConfig.ofDefaults();
        body = "{\"memberId\":\"7\",\"problemId\":\"301\",\"assigned\":false}";
        for (int call = 0; call < config.getSlidingWindowSize(); call++) {
            assertThat(client.isAssignedProblem(7, 301)).isFalse();
        }
        body = "{\"memberId\":\"7\",\"problemId\":\"301\",\"assigned\":true}";
        assertThat(client.isAssignedProblem(7, 301)).isTrue();

        status = 503;
        for (int call = 0; call < config.getSlidingWindowSize(); call++) {
            assertThat(client.isAssignedProblem(7, 301)).isFalse();
        }
        path.set(null);
        status = 200;
        assertThat(client.isAssignedProblem(7, 301)).isFalse();
        assertThat(path.get()).isNull();
    }

    /**
     * HTTP(S) 호스트가 없는 Study 설정은 검증에서 거절한다.
     *
     * @param address 유효하지 않은 Study 주소
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "ftp://study-service",
            "/study-service",
            "http:/study-service"
        }
    )
    @DisplayName("호스트를 포함한 HTTP(S) 주소만 Study 설정에 허용한다")
    void rejectsInvalidStudyBaseUrl(String address) {
        URI internalUrl = URI.create("http://study-service:8080");
        var properties
            = new SubmissionClientProperties(internalUrl, internalUrl, URI.create(address), Duration.ofSeconds(2));
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            assertThat(factory.getValidator().validate(properties))
                .singleElement()
                .satisfies(
                    violation -> assertThat(violation.getPropertyPath().toString()).isEqualTo("studyBaseUrlValid")
                );
        }
    }

    /** Problem 미존재와 다른 서비스 장애를 구분한다. */
    @Test
    @DisplayName("Problem 404는 제출 불가이고 Member 404는 검증 의존성 실패다")
    void mapsNotFound() {
        status = 404;
        body = "{\"sensitive\":\"not-public\"}";
        assertCode(() -> client.validation(301, 1001), "PROBLEM_NOT_AVAILABLE");
        assertCode(() -> client.status(7), "COMMON_DEPENDENCY_UNAVAILABLE");
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
        assertCode(() -> client.levels(7), "COMMON_DEPENDENCY_UNAVAILABLE");
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
        assertCode(() -> client.status(7), "COMMON_DEPENDENCY_UNAVAILABLE");
    }

    /**
     * 공개 오류 코드만 전달되는지 확인한다.
     *
     * @param action 실패할 클라이언트 호출
     * @param code 기대 오류 코드
     */
    private void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(JudgeException.class, exception -> {
            assertThat(exception.getErrorCode().getCode()).isEqualTo(code);
            assertThat(exception.getMessage()).doesNotContain("not-public");
        });
    }
}
