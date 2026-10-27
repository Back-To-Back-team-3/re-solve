package com.backtoback.judge.global;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.judge.global.config.CommonConfiguration;
import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.global.security.SecurityConfiguration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 공통 모듈 적용 후 인증·오류 응답·요청 추적 계약을 검증한다.
 */
@WebMvcTest(JudgeWebTest.TestController.class)
@Import(
    {
        JudgeWebTest.TestController.class,
        SecurityConfiguration.class,
        CommonConfiguration.class
    }
)
@ExtendWith(OutputCaptureExtension.class)
class JudgeWebTest {
    @Autowired
    private MockMvc mvc;

    /**
     * 익명 요청에 공통 인증 오류와 요청 추적 ID를 반환하는지 검증한다.
     *
     * @throws Exception 테스트 요청 처리 중 오류가 발생한 경우
     */
    @Test
    @DisplayName("익명 요청은 공통 인증 오류와 요청 추적 ID를 반환한다")
    void anonymousRequestIsRejectedWithCommonErrorAndCorrelationId() throws Exception {
        mvc
            .perform(get("/api/v1/test"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"))
            .andExpect(header().exists("X-Correlation-Id"));
        assertThat(MDC.get("correlationId")).isNull();
    }

    /**
     * Gateway 사용자 정보와 추적 ID가 컨트롤러까지 전달되는지 검증한다.
     *
     * @throws Exception 테스트 요청 처리 중 오류가 발생한 경우
     */
    @Test
    @DisplayName("Gateway 사용자 정보와 요청 추적 ID를 Controller에 전달한다")
    void gatewayIdentityAndCorrelationIdReachController() throws Exception {
        mvc
            .perform(
                get("/api/v1/test")
                    .header("X-User-Id", "12")
                    .header("X-User-Role", "USER")
                    .header("X-Correlation-Id", "request-123")
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userId").value("12"))
            .andExpect(jsonPath("$.message").isEmpty())
            .andExpect(jsonPath("$.data.correlationId").value("request-123"))
            .andExpect(header().string("X-Correlation-Id", "request-123"));
        assertThat(MDC.get("correlationId")).isNull();
    }

    /**
     * 일반 사용자의 관리자 API 접근을 거부하는지 검증한다.
     *
     * @throws Exception 테스트 요청 처리 중 오류가 발생한 경우
     */
    @Test
    @DisplayName("일반 사용자의 관리자 API 접근을 거부한다")
    void ordinaryUserCannotCallAdminApi() throws Exception {
        mvc
            .perform(get("/api/v1/admin/test").header("X-User-Id", "12").header("X-User-Role", "USER"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("AUTH_ACCESS_DENIED"));
    }

    /**
     * 관리자의 관리자 API 접근을 허용하는지 검증한다.
     *
     * @throws Exception 테스트 요청 처리 중 오류가 발생한 경우
     */
    @Test
    @DisplayName("관리자의 관리자 API 접근을 허용한다")
    void administratorCanCallAdminApi() throws Exception {
        mvc
            .perform(get("/api/v1/admin/test").header("X-User-Id", "12").header("X-User-Role", "ADMIN"))
            .andExpect(status().isOk());
    }

    /**
     * 누락되거나 지원하지 않는 Gateway 인증 정보를 거부하는지 검증한다.
     *
     * @throws Exception 테스트 요청 처리 중 오류가 발생한 경우
     */
    @Test
    @DisplayName("Gateway 사용자 정보가 누락되거나 지원하지 않는 역할이면 인증을 거부한다")
    void incompleteGatewayIdentityIsRejected() throws Exception {
        mvc.perform(get("/api/v1/test").header("X-User-Id", "12")).andExpect(status().isUnauthorized());
        mvc
            .perform(get("/api/v1/test").header("X-User-Id", "12").header("X-User-Role", "SERVICE"))
            .andExpect(status().isUnauthorized());
    }

    /**
     * 내부 요청을 인증 없이 허용하고 내부 응답을 공통 형식으로 감싸지 않는지 검증한다.
     *
     * @throws Exception 테스트 요청 처리 중 오류가 발생한 경우
     */
    @Test
    @DisplayName("내부 API는 인증 없이 허용하고 데이터 객체를 그대로 반환한다")
    void internalRequestsAreAllowedWithoutAuthenticationAndReturnUnwrappedData() throws Exception {
        mvc
            .perform(get("/internal/v1/test"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.value").value("internal"))
            .andExpect(jsonPath("$.success").doesNotExist());
        mvc.perform(get("/internal/v1/test").header("Authorization", "Bearer unknown")).andExpect(status().isOk());
    }

    /**
     * 입력 검증 실패 시 공통 오류와 필드 정보를 반환하는지 검증한다.
     *
     * @throws Exception 테스트 요청 처리 중 오류가 발생한 경우
     */
    @Test
    @DisplayName("요청 검증 실패는 공통 오류 코드와 필드 정보를 반환한다")
    void validationFailureUsesCommonError() throws Exception {
        mvc
            .perform(
                post("/api/v1/test")
                    .header("X-User-Id", "12")
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"\"}")
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"))
            .andExpect(jsonPath("$.error.details[0].field").value("name"));
    }

    /**
     * 잘못된 요청 본문과 인증 토큰이 응답이나 로그에 노출되지 않는지 검증한다.
     *
     * @param output 테스트 실행 중 캡처한 로그 출력
     * @throws Exception 테스트 요청 처리 중 오류가 발생한 경우
     */
    @Test
    @DisplayName("잘못된 요청 본문과 토큰을 응답과 로그에 노출하지 않는다")
    void malformedBodyIsNotIncludedInResponseOrLogs(CapturedOutput output) throws Exception {
        mvc
            .perform(
                post("/api/v1/test")
                    .header("X-User-Id", "12")
                    .header("X-User-Role", "USER")
                    .header("Authorization", "Bearer secret-token")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{secret-source-code")
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
        assertThat(output.getAll()).doesNotContain("secret-source-code", "secret-token");
    }

    /**
     * 필수 파라미터 누락과 타입 오류의 400 상태가 유지되는지 검증한다.
     *
     * @throws Exception 테스트 요청 처리 중 오류가 발생한 경우
     */
    @Test
    @DisplayName("필수 파라미터 누락과 타입 오류는 400 상태를 유지한다")
    void missingOrInvalidQueryParameterRemainsBadRequest() throws Exception {
        mvc
            .perform(get("/api/v1/test/number").header("X-User-Id", "12").header("X-User-Role", "USER"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
        mvc
            .perform(
                get("/api/v1/test/number").param("value", "abc").header("X-User-Id", "12").header("X-User-Role", "USER")
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    /**
     * 지원하지 않는 HTTP 메서드의 상태와 Allow 헤더를 유지하는지 검증한다.
     *
     * @throws Exception 테스트 요청 처리 중 오류가 발생한 경우
     */
    @Test
    @DisplayName("지원하지 않는 HTTP 메서드는 405 상태와 Allow 헤더를 유지한다")
    void unsupportedMethodPreservesStatusAndAllowHeader() throws Exception {
        mvc
            .perform(put("/api/v1/test").header("X-User-Id", "12").header("X-User-Role", "USER"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")))
            .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("POST")))
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    /**
     * 지원하지 않는 요청 본문 형식에 415 상태를 반환하는지 검증한다.
     *
     * @throws Exception 테스트 요청 처리 중 오류가 발생한 경우
     */
    @Test
    @DisplayName("지원하지 않는 요청 본문 형식은 415 상태를 반환한다")
    void unsupportedContentTypeRemainsUnsupportedMediaType() throws Exception {
        mvc
            .perform(
                post("/api/v1/test")
                    .header("X-User-Id", "12")
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.TEXT_PLAIN)
                    .content("invalid-input")
            )
            .andExpect(status().isUnsupportedMediaType())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    /**
     * 지원하지 않는 응답 형식 요청에 406 상태를 반환하는지 검증한다.
     *
     * @throws Exception 테스트 요청 처리 중 오류가 발생한 경우
     */
    @Test
    @DisplayName("지원하지 않는 응답 형식은 406 상태를 반환한다")
    void unsupportedAcceptHeaderRemainsNotAcceptable() throws Exception {
        mvc
            .perform(
                get("/api/v1/test/image")
                    .header("X-User-Id", "12")
                    .header("X-User-Role", "USER")
                    .accept(MediaType.APPLICATION_JSON)
            )
            .andExpect(status().isNotAcceptable())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    /**
     * 없는 리소스 요청의 404 상태를 유지하는지 검증한다.
     *
     * @throws Exception 테스트 요청 처리 중 오류가 발생한 경우
     */
    @Test
    @DisplayName("없는 리소스 요청은 404 상태를 유지한다")
    void missingResourceRemainsNotFound() throws Exception {
        mvc
            .perform(get("/api/v1/test/missing").header("X-User-Id", "12").header("X-User-Role", "USER"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    /**
     * 명시된 서버 오류 상태를 유지하고 예외 종류·위치만 로그에 남기는지 검증한다.
     *
     * @param output 테스트 실행 중 캡처한 로그 출력
     * @throws Exception 테스트 요청 처리 중 오류가 발생한 경우
     */
    @Test
    @DisplayName("명시한 서버 오류 상태를 유지하고 로그에는 안전한 진단 정보만 기록한다")
    void explicitServerErrorPreservesStatusAndLogsSafeDiagnostics(CapturedOutput output) throws Exception {
        mvc
            .perform(get("/api/v1/test/unavailable").header("X-User-Id", "12").header("X-User-Role", "USER"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("COMMON_INTERNAL_SERVER_ERROR"))
            .andExpect(jsonPath("$.error.message").value("서버 오류가 발생했습니다."));
        assertThat(output.getAll())
            .contains("ResponseStatusException", "TestController.unavailable")
            .doesNotContain("secret-exception-body");
    }

    /**
     * 예상하지 못한 예외의 종류·위치를 기록하고 원문은 응답과 로그에서 숨기는지 검증한다.
     *
     * @param output 테스트 실행 중 캡처한 로그 출력
     * @throws Exception 테스트 요청 처리 중 오류가 발생한 경우
     */
    @Test
    @DisplayName("예상하지 못한 오류는 공통 응답을 반환하고 로그에 원문을 노출하지 않는다")
    void unexpectedErrorLogsExceptionWithoutExposingItInResponse(CapturedOutput output) throws Exception {
        mvc
            .perform(get("/api/v1/test/failure").header("X-User-Id", "12").header("X-User-Role", "USER"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code").value("COMMON_INTERNAL_SERVER_ERROR"))
            .andExpect(jsonPath("$.error.message").value("서버 오류가 발생했습니다."));
        assertThat(output.getAll())
            .contains("IllegalStateException", "TestController.failure")
            .doesNotContain("secret-exception-body");
    }

    /**
     * 본문 없는 응답과 바이너리 응답을 공통 JSON 형식으로 감싸지 않는지 검증한다.
     *
     * @throws Exception 테스트 요청 처리 중 오류가 발생한 경우
     */
    @Test
    @DisplayName("본문 없는 응답과 바이너리 응답을 공통 JSON 형식으로 감싸지 않는다")
    void noContentAndBinaryResponsesAreNotWrapped() throws Exception {
        mvc
            .perform(get("/api/v1/test/empty").header("X-User-Id", "12").header("X-User-Role", "USER"))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));
        mvc
            .perform(get("/api/v1/test/image").header("X-User-Id", "12").header("X-User-Role", "USER"))
            .andExpect(status().isOk())
            .andExpect(
                content()
                    .bytes(
                        new byte[] {
                            1,
                            2,
                            3
                        }
                    )
            );
    }

    /**
     * Judge0 장애 유형별 오류 코드와 안내 메시지를 반환하고 내부 HTTP 상태는 숨기는지 검증한다.
     *
     * @param code 기대하는 오류 코드
     * @param message 기대하는 안내 메시지
     * @throws Exception 테스트 요청 처리 중 오류가 발생한 경우
     */
    @ParameterizedTest
    @DisplayName("Judge0 장애별 오류 코드와 안내를 반환하고 외부 HTTP 상태는 숨긴다")
    @CsvSource(
        {
            "JUDGE0_CONNECTION_FAILED, 채점 서버에 연결할 수 없습니다. 잠시 후 다시 시도해 주세요.",
            "JUDGE0_RESPONSE_FAILED, 채점 서버의 응답을 처리할 수 없습니다. 잠시 후 다시 시도해 주세요.",
            "JUDGE0_CONFIGURATION_INVALID, 채점 서버 설정이 준비되지 않았습니다. 잠시 후 다시 시도해 주세요."
        }
    )
    void judge0FailuresReturnDistinctErrorCodes(String code, String message) throws Exception {
        mvc
            .perform(
                get("/api/v1/test/judge0").param("code", code).header("X-User-Id", "12").header("X-User-Role", "USER")
            )
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value(code))
            .andExpect(jsonPath("$.error.message").value(message))
            .andExpect(jsonPath("$.error.upstreamStatus").doesNotExist());
    }

    /**
     * 재채점 실행 환경 불일치를 팀 명세의 409 오류로 반환하는지 검증한다.
     *
     * @throws Exception 테스트 요청 처리 중 오류가 발생한 경우
     */
    @Test
    @DisplayName("기존 실행 환경을 사용할 수 없으면 재채점에 409 오류를 반환한다")
    void unavailableRejudgeRuntimeReturnsConflict() throws Exception {
        mvc
            .perform(
                get("/api/v1/test/judge0")
                    .param("code", "SUBMISSION_RUNTIME_UNAVAILABLE")
                    .header("X-User-Id", "12")
                    .header("X-User-Role", "USER")
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("SUBMISSION_RUNTIME_UNAVAILABLE"))
            .andExpect(jsonPath("$.error.message").value("기존 실행 환경을 사용할 수 없어 재채점할 수 없습니다."));
    }

    /**
     * 공통 웹 계약을 검증할 경로와 실패 조건을 제공한다.
     */
    @RestController
    static class TestController {
        /**
         * 지정한 Judge0 장애의 공통 예외 응답을 검증하기 위해 예외를 발생시킨다.
         *
         * @param code 검증할 Judge0 오류 코드
         * @throws JudgeException 지정한 채점 오류를 공통 처리기로 전달하는 경우
         */
        @GetMapping("/api/v1/test/judge0")
        void judge0Failure(@RequestParam JudgeErrorCode code) {
            if (code == JudgeErrorCode.JUDGE0_RESPONSE_FAILED) {
                throw new JudgeException(code, 401);
            }
            throw new JudgeException(code);
        }

        /**
         * 인증 사용자와 추적 ID를 테스트 응답으로 반환한다.
         *
         * @param user 현재 인증된 Gateway 사용자
         * @return 사용자 ID와 요청 추적 ID
         */
        @GetMapping(
            {
                "/api/v1/test",
                "/api/v1/admin/test"
            }
        )
        ApiResponse<Map<String, String>> identity(@AuthenticationPrincipal GatewayUser user) {
            return ApiResponse.success(Map.of("userId", user.userId(), "correlationId", MDC.get("correlationId")));
        }

        /**
         * 내부 API의 공통 응답 미적용 여부를 확인할 데이터를 반환한다.
         *
         * @return 공통 응답으로 감싸지 않은 테스트 데이터
         */
        @GetMapping("/internal/v1/test")
        Map<String, String> internal() {
            return Map.of("value", "internal");
        }

        /**
         * 요청 본문 검증 후 입력값을 그대로 응답한다.
         *
         * @param input 검증할 테스트 요청 본문
         * @return 검증된 입력값의 성공 응답
         */
        @PostMapping("/api/v1/test")
        ApiResponse<Input> validate(@Valid @RequestBody Input input) {
            return ApiResponse.success(input);
        }

        /**
         * 예상하지 못한 서버 오류 응답을 검증하기 위해 예외를 발생시킨다.
         *
         * @throws IllegalStateException 이 테스트 경로를 호출한 경우
         */
        @GetMapping("/api/v1/test/failure")
        void failure() {
            throw new IllegalStateException("secret-exception-body");
        }

        /**
         * 숫자 파라미터 변환을 검증하기 위해 입력값을 반환한다.
         *
         * @param value 요청에서 변환한 정수
         * @return 변환된 정수 파라미터
         */
        @GetMapping("/api/v1/test/number")
        int number(@RequestParam int value) {
            return value;
        }

        /**
         * 지정한 서버 오류 상태의 유지 여부를 검증하기 위해 예외를 발생시킨다.
         *
         * @throws ResponseStatusException 이 테스트 경로를 호출한 경우
         */
        @GetMapping("/api/v1/test/unavailable")
        void unavailable() {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "secret-exception-body");
        }

        /**
         * 본문 없는 응답 처리를 검증하기 위해 204 응답을 반환한다.
         *
         * @return 본문이 없는 204 응답
         */
        @GetMapping("/api/v1/test/empty")
        ResponseEntity<Void> empty() {
            return ResponseEntity.noContent().build();
        }

        /**
         * 바이너리 응답 처리를 검증하기 위해 고정 바이트를 반환한다.
         *
         * @return 테스트용 바이트 배열
         */
        @GetMapping(
            value = "/api/v1/test/image",
            produces = MediaType.IMAGE_PNG_VALUE
        )
        byte[] image() {
            return new byte[] {
                1,
                2,
                3
            };
        }
    }

    /**
     * 요청 본문 검증용 입력.
     *
     * @param name 필수 입력 이름
     */
    record Input(@NotBlank(message = "이름은 필수입니다.") String name) {
    }
}
