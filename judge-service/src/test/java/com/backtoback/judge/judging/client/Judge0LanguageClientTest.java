package com.backtoback.judge.judging.client;

import static com.backtoback.judge.judging.config.Judge0TestProperties.properties;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.io.IOException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.dto.response.Judge0LanguageResponse;

/**
 * 언어 목록 통신의 응답 검증과 오류 분류를 검증한다.
 */
class Judge0LanguageClientTest {
    private MockRestServiceServer server;
    private Judge0LanguageClient client;

    /**
     * 외부 서버 없이 언어 목록 조회 계약을 검증할 클라이언트를 준비한다.
     */
    @BeforeEach
    void setUp() {
        var builder = RestClient.builder().baseUrl("http://judge0.test");
        server = MockRestServiceServer.bindTo(builder).build();
        client = new Judge0LanguageClient(properties("http://judge0.test"), builder.build());
    }

    /**
     * 클라이언트는 지원 언어·버전을 선택하거나 캐시하지 않고 전체 목록을 반환하는지 검증한다.
     */
    @Test
    @DisplayName("클라이언트는 지원 언어·버전을 선택하거나 캐시하지 않고 전체 목록을 반환하는지 검증한다")
    void returnsEntireCatalogWithoutSelectionOrCaching() {
        server.expect(requestTo("http://judge0.test/languages/")).andRespond(withSuccess("""
            [{"id":1,"name":"Java (JDK 17.0.6)"},
             {"id":2,"name":"Java (JDK 17.0.10)"},
             {"id":3,"name":"Ruby (2.7.0)"}]
            """, MediaType.APPLICATION_JSON));
        server
            .expect(requestTo("http://judge0.test/languages/"))
            .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        var languages = client.findAll();
        assertThat(languages)
            .containsExactly(
                new Judge0LanguageResponse(1, "Java (JDK 17.0.6)"),
                new Judge0LanguageResponse(2, "Java (JDK 17.0.10)"),
                new Judge0LanguageResponse(3, "Ruby (2.7.0)")
            );
        assertThatThrownBy(languages::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(client.findAll()).isEmpty();
        server.verify();
    }

    /**
     * 성공 계열 상태라도 목록이 없는 204 응답은 재시도 없이 응답 오류로 처리하는지 검증한다.
     */
    @Test
    @DisplayName("성공 계열 상태라도 목록이 없는 204 응답은 재시도 없이 응답 오류로 처리하는지 검증한다")
    void rejectsMissingCatalogWithoutRetry() {
        server.expect(requestTo("http://judge0.test/languages/")).andRespond(withStatus(HttpStatus.NO_CONTENT));
        assertThatThrownBy(() -> client.findAll())
            .isInstanceOfSatisfying(
                JudgeException.class,
                exception -> assertThat(exception.getUpstreamStatus()).isEqualTo(204)
            );
        server.verify();
    }

    /**
     * 연결 실패를 전용 예외로 변환하고 클라이언트 내부에서 재시도하지 않는지 검증한다.
     */
    @Test
    @DisplayName("연결 실패를 전용 예외로 변환하고 클라이언트 내부에서 재시도하지 않는지 검증한다")
    void reportsConnectionFailureWithoutRetry() {
        server.expect(requestTo("http://judge0.test/languages/")).andRespond(request -> {
            throw new IOException("connection failed");
        });
        assertThatThrownBy(() -> client.findAll())
            .isInstanceOfSatisfying(
                JudgeException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(JudgeErrorCode.JUDGE0_CONNECTION_FAILED)
            );
        server.verify();
    }
}
