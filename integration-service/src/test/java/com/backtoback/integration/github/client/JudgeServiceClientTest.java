package com.backtoback.integration.github.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.integration.github.client.dto.AcceptedSubmissionCodeResponse;
import com.backtoback.integration.github.config.JudgeProperties;
import com.backtoback.integration.global.error.IntegrationErrorCode;
import com.backtoback.integration.global.error.IntegrationException;

class JudgeServiceClientTest {

    private JudgeServiceClient client;
    private MockRestServiceServer server;

    @BeforeEach
    void setUp() {
        JudgeProperties properties = new JudgeProperties();
        properties.setBaseUrl("http://judge-service:8080");

        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        client = new JudgeServiceClient(properties, builder.build());
    }

    @Test
    @DisplayName("정답 코드 조회가 성공하면 언어·코드·제출 시각을 반환한다")
    void getAcceptedCodeSuccess() {
        server
            .expect(requestTo("http://judge-service:8080/internal/v1/submissions/91023/accepted/code?memberId=7"))
            .andExpect(method(HttpMethod.GET))
            .andRespond(withSuccess("""
                {"submissionId":"91023","problemId":"301","language":"python3","sourceCode":"print(1)",
                 "judgeAttempt":1,"submittedAt":"2026-09-28T10:00:00+09:00"}
                """, MediaType.APPLICATION_JSON));

        AcceptedSubmissionCodeResponse response = client.getAcceptedCode(91023L, 7L);

        assertThat(response.language()).isEqualTo("python3");
        assertThat(response.sourceCode()).isEqualTo("print(1)");
        assertThat(response.judgeAttempt()).isEqualTo(1);
        assertThat(response.submittedAt()).isNotNull();
        server.verify();
    }

    @Test
    @DisplayName("거절(4xx)·서버 오류 응답은 의존 서비스 장애 예외로 변환한다")
    void getAcceptedCodeFailure() {
        server
            .expect(requestTo("http://judge-service:8080/internal/v1/submissions/1/accepted/code?memberId=7"))
            .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> client.getAcceptedCode(1L, 7L))
            .isInstanceOfSatisfying(
                IntegrationException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE)
            );
    }

    @Test
    @DisplayName("코드나 언어가 비어 있는 응답은 장애 예외로 처리한다")
    void getAcceptedCodeEmptyBody() {
        server
            .expect(requestTo("http://judge-service:8080/internal/v1/submissions/2/accepted/code?memberId=7"))
            .andRespond(withSuccess("{\"submissionId\":\"2\"}", MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> client.getAcceptedCode(2L, 7L)).isInstanceOf(IntegrationException.class);
    }

    @Test
    @DisplayName("수동 동기화 검증 시 judge-service가 404 또는 409를 반환하면 GITHUB_SYNC_NOT_ALLOWED(409) 예외로 변환한다")
    void verifyAndGetAcceptedCodeForManualSyncClientError() {
        server
            .expect(requestTo("http://judge-service:8080/internal/v1/submissions/999/accepted/code?memberId=7"))
            .andRespond(withStatus(HttpStatus.CONFLICT));

        assertThatThrownBy(() -> client.verifyAndGetAcceptedCodeForManualSync(999L, 7L))
            .isInstanceOfSatisfying(
                IntegrationException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(IntegrationErrorCode.GITHUB_SYNC_NOT_ALLOWED)
            );
    }

    @Test
    @DisplayName("수동 동기화 검증 시 judge-service가 5xx를 반환하면 COMMON_DEPENDENCY_UNAVAILABLE(503) 예외로 변환한다")
    void verifyAndGetAcceptedCodeForManualSyncServerError() {
        server
            .expect(requestTo("http://judge-service:8080/internal/v1/submissions/999/accepted/code?memberId=7"))
            .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> client.verifyAndGetAcceptedCodeForManualSync(999L, 7L))
            .isInstanceOfSatisfying(
                IntegrationException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE)
            );
    }
}
