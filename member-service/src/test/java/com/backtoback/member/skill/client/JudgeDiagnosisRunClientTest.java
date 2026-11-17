package com.backtoback.member.skill.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.member.global.error.MemberErrorCode;
import com.backtoback.member.global.error.MemberException;

/** Judge 호출 경계에서 입력·결과·오류와 원인 보존을 확인한다. */
class JudgeDiagnosisRunClientTest {
    private final RestClient.Builder builder = RestClient.builder().baseUrl("http://judge");
    private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
    private final JudgeDiagnosisRunClient client = new JudgeDiagnosisRunClient(builder.build());

    /** 고정 회차·문맥과 상관 ID를 그대로 전송하고 디버그 출력을 보존한다. */
    @Test
    @DisplayName("진단 Run은 고정 회차와 상관 ID를 전달하고 반환값·디버그 출력을 구분한다")
    void forwardsPinnedContextAndTrace() {
        server
            .expect(requestTo("http://judge/internal/v1/judge/runs"))
            .andExpect(header("X-Correlation-Id", "run-test"))
            .andExpect(content().json("""
                {"context":"DIAGNOSIS","contextId":"120","memberId":"7","problemId":"301",
                "problemRevisionId":"1001","language":"java","sourceCode":"code","customTests":[]}
                """))
            .andRespond(withSuccess("""
                {"results":[{"type":"SAMPLE","index":0,"output":"3","stdout":"debug","error":null,
                "timeMs":2,"memoryKb":1024}],"completedAt":"2026-11-16T00:00:00Z"}
                """, MediaType.APPLICATION_JSON));
        MDC.put("correlationId", "run-test");
        try {
            var result = client.run(request());
            assertThat(result.results().getFirst().output()).isEqualTo("3");
            assertThat(result.results().getFirst().stdout()).isEqualTo("debug");
        } finally {
            MDC.clear();
        }
        server.verify();
    }

    /**
     * 알려진 오류는 원격 상태·코드가 모두 일치해야 전달한다.
     *
     * @param status 원격 HTTP 상태
     * @param code 기존 Run 오류 코드
     */
    @ParameterizedTest
    @CsvSource(
        {
            "429,RUN_IN_PROGRESS",
            "504,RUN_TIMEOUT",
            "413,CODE_TOO_LARGE",
            "409,SUBMISSION_RUNTIME_UNAVAILABLE"
        }
    )
    @DisplayName("알려진 Run 오류는 HTTP 상태와 원인 예외를 보존한다")
    void preservesRunErrors(int status, String code) {
        server
            .expect(requestTo("http://judge/internal/v1/judge/runs"))
            .andRespond(
                withStatus(HttpStatus.valueOf(status))
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(
                        "{\"success\":false,\"error\":{\"code\":\"" + code
                            + "\",\"message\":\"failure\",\"details\":[]}}"
                    )
            );
        assertThatThrownBy(() -> client.run(request())).isInstanceOfSatisfying(MemberException.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo(MemberErrorCode.valueOf(code));
            assertThat(failure.getCause()).isNotNull();
        });
    }

    /** 계약에 없는 결과 유형은 숨김 테스트를 포함할 수 있으므로 반환하지 않는다. */
    @Test
    @DisplayName("숨김 결과 유형이나 불완전한 Judge 응답은 503으로 차단한다")
    void rejectsUnexpectedResult() {
        server.expect(requestTo("http://judge/internal/v1/judge/runs")).andRespond(withSuccess("""
            {"results":[{"type":"HIDDEN","index":0,"output":"secret","stdout":"","error":null,
            "timeMs":2,"memoryKb":1024}],"completedAt":"2026-11-16T00:00:00Z"}
            """, MediaType.APPLICATION_JSON));
        assertThatThrownBy(() -> client.run(request()))
            .isInstanceOfSatisfying(
                MemberException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE)
            );
    }

    /**
     * 기존 입력을 저장 없이 실행하는 요청을 만든다.
     *
     * @return Member가 확인한 실행 요청
     */
    private JudgeDiagnosisRunClient.RunRequest request() {
        return new JudgeDiagnosisRunClient.RunRequest(
            "DIAGNOSIS",
            "120",
            "7",
            "301",
            "1001",
            "java",
            "code",
            List.of()
        );
    }
}
