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

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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

import com.backtoback.judge.global.api.ApiResponse;
import com.backtoback.judge.global.exception.GlobalExceptionHandler;
import com.backtoback.judge.global.logging.CorrelationIdFilter;
import com.backtoback.judge.global.security.SecurityConfiguration;
import com.backtoback.judge.global.security.user.GatewayUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@WebMvcTest
@Import(
    {
        JudgeWebTests.TestController.class,
        SecurityConfiguration.class,
        GlobalExceptionHandler.class,
        CorrelationIdFilter.class
    }
)
@ExtendWith(OutputCaptureExtension.class)
class JudgeWebTests {
    @Autowired
    private MockMvc mvc;

    /**
     * 익명 요청에 공통 인증 오류와 요청 추적 ID를 반환하는지 검증한다.
     *
     * @throws Exception 테스트 요청 처리 중 오류가 발생한 경우
     */
    @Test
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
    void missingResourceRemainsNotFound() throws Exception {
        mvc
            .perform(get("/api/v1/test/missing").header("X-User-Id", "12").header("X-User-Role", "USER"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    /**
     * 명시된 서버 오류 상태를 유지하고 상세 원인은 로그에 남기는지 검증한다.
     *
     * @param output 테스트 실행 중 캡처한 로그 출력
     * @throws Exception 테스트 요청 처리 중 오류가 발생한 경우
     */
    @Test
    void explicitServerErrorPreservesStatusAndLogsReason(CapturedOutput output) throws Exception {
        mvc
            .perform(get("/api/v1/test/unavailable").header("X-User-Id", "12").header("X-User-Role", "USER"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("COMMON_INTERNAL_SERVER_ERROR"))
            .andExpect(jsonPath("$.error.message").value("서버 오류가 발생했습니다."));
        assertThat(output.getAll())
            .contains("ResponseStatusException", "secret-exception-body", "TestController.unavailable");
    }

    /**
     * 예상하지 못한 예외는 로그에 기록하고 응답에서는 상세 내용을 숨기는지 검증한다.
     *
     * @param output 테스트 실행 중 캡처한 로그 출력
     * @throws Exception 테스트 요청 처리 중 오류가 발생한 경우
     */
    @Test
    void unexpectedErrorLogsExceptionWithoutExposingItInResponse(CapturedOutput output) throws Exception {
        mvc
            .perform(get("/api/v1/test/failure").header("X-User-Id", "12").header("X-User-Role", "USER"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code").value("COMMON_INTERNAL_SERVER_ERROR"))
            .andExpect(jsonPath("$.error.message").value("서버 오류가 발생했습니다."));
        assertThat(output.getAll())
            .contains("IllegalStateException", "secret-exception-body", "TestController.failure");
    }

    /**
     * 본문 없는 응답과 바이너리 응답을 공통 JSON 형식으로 감싸지 않는지 검증한다.
     *
     * @throws Exception 테스트 요청 처리 중 오류가 발생한 경우
     */
    @Test
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

    @RestController
    static class TestController {
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

    record Input(@NotBlank String name) {
    }
}
