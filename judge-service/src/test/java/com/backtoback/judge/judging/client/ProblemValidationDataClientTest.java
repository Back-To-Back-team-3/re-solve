package com.backtoback.judge.judging.client;

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
import com.backtoback.judge.judging.dto.ProblemValidationRequest;
import com.backtoback.judge.submission.config.SubmissionClientProperties;
import com.sun.net.httpserver.HttpServer;

/** 검증 자료 HTTP 계약·추적 전파·잘못된 원문 차단을 확인한다. */
class ProblemValidationDataClientTest {
    private static final String HASH = "a".repeat(64);
    private final ProblemValidationRequest request = new ProblemValidationRequest(2001, 1001, "java", HASH);
    private HttpServer server;
    private ProblemValidationDataClient client;
    private String body;
    private int status;
    private final AtomicReference<String> path = new AtomicReference<>();
    private final AtomicReference<String> correlation = new AtomicReference<>();

    /**
     * 명세 형태의 고정 검증 자료를 로컬 서버에 준비한다.
     *
     * @throws Exception 서버 생성 실패
     */
    @BeforeEach
    void prepare() throws Exception {
        status = 200;
        body = """
            {"validationId":"2001","problemRevisionId":"1001","language":"java","requestHash":"%s",
            "sourceCode":"class Solution {}","functionSpec":{"name":"solution","parameters":[],
            "returnType":{"kind":"INT32"}},
            "executionLimits":{"timeMs":2000,"wallTimeMs":7000,"memoryKb":262144,"outputLimitKb":64},
            "testCases":[{"testAssetId":"501","isSample":false,"argumentTexts":{},"expectedReturnText":"3"}]}
            """.formatted(HASH);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            path.set(exchange.getRequestURI().getPath());
            correlation.set(exchange.getRequestHeaders().getFirst("X-Correlation-Id"));
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var stream = exchange.getResponseBody()) {
                stream.write(bytes);
            }
        });
        server.start();
        var uri = URI.create("http://127.0.0.1:" + server.getAddress().getPort());
        var builder = RestClient.builder();
        try (var context = new AnnotationConfigApplicationContext(HttpClientConfiguration.class)) {
            context.getBean(RestClientCustomizer.class).customize(builder);
        }
        client
            = new ProblemValidationDataClient(builder, new SubmissionClientProperties(uri, uri, Duration.ofSeconds(2)));
    }

    /** 서버와 스레드 추적 정보를 정리한다. */
    @AfterEach
    void close() {
        server.stop(0);
        MDC.clear();
    }

    /** 고정 ID 경로와 추적 헤더를 유지하며 직접 객체를 읽는다. */
    @Test
    @DisplayName("고정 검증 자료를 조회하고 원 요청 Correlation ID를 전달한다")
    void readsFixedData() {
        MDC.put("correlationId", "validation-2001");
        var response = client.find(request);
        assertThat(path.get()).isEqualTo("/internal/v1/problems/validations/2001/data");
        assertThat(correlation.get()).isEqualTo("validation-2001");
        assertThat(response.testCases()).hasSize(1);
        assertThat(response.sourceCode()).isEqualTo("class Solution {}");
    }

    /**
     * 원 요청과 다른 자료를 실행하지 않는다.
     *
     * @param original 바꿀 응답 값
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "2001",
            "1001",
            "java",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        }
    )
    @DisplayName("검증 ID·회차·언어·해시 중 하나라도 다르면 의존성 실패로 처리한다")
    void rejectsMismatchedData(String original) {
        body = body.replace(original, "mismatch");
        assertUnavailable();
    }

    /**
     * 외부 서버 오류를 코드 판정으로 바꾸거나 응답 원문을 노출하지 않는다.
     *
     * @param responseStatus 외부 응답 상태
     */
    @ParameterizedTest
    @ValueSource(
        ints = {
            404,
            500,
            503
        }
    )
    @DisplayName("자료 조회 실패는 원문을 노출하지 않는 의존성 오류다")
    void rejectsHttpFailure(int responseStatus) {
        status = responseStatus;
        body = "private-source-code";
        assertUnavailable();
    }

    /** 전체 테스트가 없는 응답으로 성공 판정하지 않는다. */
    @Test
    @DisplayName("테스트가 없는 검증 자료는 거부한다")
    void rejectsMissingTests() {
        body = body.replace("\"testCases\"", "\"ignored\"");
        assertUnavailable();
    }

    /** 잘못된 실행 제한을 Judge0에 전달하지 않는다. */
    @Test
    @DisplayName("최대 반환값 제한을 초과한 자료는 거부한다")
    void rejectsInvalidLimits() {
        body = body.replace("\"outputLimitKb\":64", "\"outputLimitKb\":257");
        assertUnavailable();
    }

    /** 의존성 오류 코드와 원문 비노출을 함께 확인한다. */
    private void assertUnavailable() {
        assertThatThrownBy(() -> client.find(request)).isInstanceOfSatisfying(JudgeException.class, failure -> {
            assertThat(failure.getErrorCode().getCode()).isEqualTo("COMMON_DEPENDENCY_UNAVAILABLE");
            assertThat(failure.getMessage()).doesNotContain("private-source-code");
        });
    }
}
