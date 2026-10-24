package com.backtoback.problem.global.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.util.Map;

import org.hibernate.exception.ConstraintViolationException;
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
import org.springframework.dao.CannotAcquireLockException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.exception.ErrorCode;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

/**
 * 공통 웹 설정과 문제 전용 예외 처리기가 함께 적용된 HTTP 응답을 확인한다.
 */
@WebMvcTest
@Import(
    {
        CommonConfigurationWebTest.TestController.class,
        SecurityConfiguration.class,
        CommonConfiguration.class,
        CorrelationIdFilter.class
    }
)
@ExtendWith(OutputCaptureExtension.class)
class CommonConfigurationWebTest {
    @Autowired
    private MockMvc mvc;

    @Test
    @DisplayName("익명 요청이면 공통 인증 오류와 요청 추적 ID를 반환한다")
    void anonymousRequestIsRejectedWithCommonErrorAndCorrelationId() throws Exception {
        // when
        mvc
            .perform(get("/api/v1/test"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value("AUTH_TOKEN_INVALID"))
            .andExpect(header().exists("X-Correlation-Id"));
        // then
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    @DisplayName("Gateway 사용자 정보와 추적 ID를 컨트롤러까지 전달한다")
    void gatewayIdentityAndCorrelationIdReachController() throws Exception {
        // when
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
        // then
        assertThat(MDC.get("correlationId")).isNull();
    }

    @Test
    @DisplayName("일반 사용자의 관리자 API 접근을 거부한다")
    void ordinaryUserCannotCallAdminApi() throws Exception {
        mvc
            .perform(get("/api/v1/admin/test").header("X-User-Id", "12").header("X-User-Role", "USER"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("AUTH_ACCESS_DENIED"));
    }

    @Test
    @DisplayName("관리자의 관리자 API 접근을 허용한다")
    void administratorCanCallAdminApi() throws Exception {
        mvc
            .perform(get("/api/v1/admin/test").header("X-User-Id", "12").header("X-User-Role", "ADMIN"))
            .andExpect(status().isOk());
    }

    @Test
    @DisplayName("Gateway 사용자 정보가 누락되거나 역할이 지원되지 않으면 인증을 거부한다")
    void incompleteGatewayIdentityIsRejected() throws Exception {
        mvc.perform(get("/api/v1/test").header("X-User-Id", "12")).andExpect(status().isUnauthorized());
        mvc
            .perform(get("/api/v1/test").header("X-User-Id", "12").header("X-User-Role", "SERVICE"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("내부 요청은 인증 없이 허용하고 응답을 공통 성공 형식으로 감싸지 않는다")
    void internalRequestsAreAllowedWithoutAuthenticationAndReturnUnwrappedData() throws Exception {
        mvc
            .perform(get("/internal/v1/test"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.value").value("internal"))
            .andExpect(jsonPath("$.success").doesNotExist());
        mvc.perform(get("/internal/v1/test").header("Authorization", "Bearer unknown")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("입력 검증에 실패하면 공통 오류와 필드 정보를 반환한다")
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

    @Test
    @DisplayName("잘못된 요청 본문과 인증 토큰을 응답이나 로그에 노출하지 않는다")
    void malformedBodyIsNotIncludedInResponseOrLogs(CapturedOutput output) throws Exception {
        // when
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
        // then
        assertThat(output.getAll()).doesNotContain("secret-source-code", "secret-token");
    }

    @Test
    @DisplayName("필수 파라미터가 누락되거나 타입이 잘못되면 400 상태를 반환한다")
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

    @Test
    @DisplayName("지원하지 않는 HTTP 메서드를 요청하면 405 상태와 허용 메서드 헤더를 반환한다")
    void unsupportedMethodPreservesStatusAndAllowHeader() throws Exception {
        mvc
            .perform(put("/api/v1/test").header("X-User-Id", "12").header("X-User-Role", "USER"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")))
            .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("POST")))
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    @Test
    @DisplayName("지원하지 않는 요청 본문 형식이면 415 상태를 반환한다")
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

    @Test
    @DisplayName("지원하지 않는 응답 형식을 요청하면 406 상태를 반환한다")
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

    @Test
    @DisplayName("없는 리소스를 요청하면 404 상태를 반환한다")
    void missingResourceRemainsNotFound() throws Exception {
        mvc
            .perform(get("/api/v1/test/missing").header("X-User-Id", "12").header("X-User-Role", "USER"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    @Test
    @DisplayName("명시된 서버 오류 상태를 유지하고 상세 원인은 응답에서 숨기고 로그에 남긴다")
    void explicitServerErrorPreservesStatusAndLogsReason(CapturedOutput output) throws Exception {
        // when
        mvc
            .perform(get("/api/v1/test/unavailable").header("X-User-Id", "12").header("X-User-Role", "USER"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("COMMON_INTERNAL_SERVER_ERROR"))
            .andExpect(jsonPath("$.error.message").value("서버 오류가 발생했습니다."));
        // then
        assertThat(output.getAll())
            .contains("ResponseStatusException", "TestController.unavailable")
            .doesNotContain("secret-exception-body");
    }

    @Test
    @DisplayName("예상하지 못한 예외는 로그에 기록하고 응답에서는 상세 내용을 숨긴다")
    void unexpectedErrorLogsExceptionWithoutExposingItInResponse(CapturedOutput output) throws Exception {
        // when
        mvc
            .perform(get("/api/v1/test/failure").header("X-User-Id", "12").header("X-User-Role", "USER"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code").value("COMMON_INTERNAL_SERVER_ERROR"))
            .andExpect(jsonPath("$.error.message").value("서버 오류가 발생했습니다."));
        // then
        assertThat(output.getAll())
            .contains("IllegalStateException", "TestController.failure")
            .doesNotContain("secret-exception-body");
    }

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

    @ParameterizedTest
    @DisplayName("공통 예외 처리기와 문제 전용 처리기가 기존 오류 계약을 유지한다")
    @CsvSource(
        {
            "duplicate,409,PROBLEM_TAG_DUPLICATED",
            "in-use,409,PROBLEM_TAG_IN_USE",
            "optimistic,409,PROBLEM_VERSION_CONFLICT",
            "lock-timeout,409,PROBLEM_VERSION_CONFLICT",
            "database-unavailable,500,COMMON_INTERNAL_SERVER_ERROR",
            "missing,404,PROBLEM_TAG_NOT_FOUND"
        }
    )
    void domainErrorsKeepTheirContract(String kind, int httpStatus, String code) throws Exception {
        mvc
            .perform(
                get("/api/v1/test/domain-error")
                    .param("kind", kind)
                    .header("X-User-Id", "12")
                    .header("X-User-Role", "USER")
            )
            .andExpect(status().is(httpStatus))
            .andExpect(jsonPath("$.error.code").value(code));
    }

    @ParameterizedTest
    @DisplayName("통합한 예외가 기존 아홉 오류의 HTTP 상태와 JSON 본문을 유지한다")
    @CsvSource(
        {
            "COMMON_INVALID_REQUEST,400,요청을 확인해 주세요.",
            "COMMON_INTERNAL_SERVER_ERROR,500,서버 오류가 발생했습니다.",
            "IDEMPOTENCY_KEY_CONFLICT,422,같은 멱등키로 다른 요청을 보낼 수 없습니다.",
            "AUTH_TOKEN_INVALID,401,인증 정보가 없거나 유효하지 않습니다.",
            "AUTH_ACCESS_DENIED,403,접근 권한이 없습니다.",
            "PROBLEM_TAG_NOT_FOUND,404,태그를 찾을 수 없습니다.",
            "PROBLEM_TAG_DUPLICATED,409,같은 이름의 태그가 이미 존재합니다.",
            "PROBLEM_TAG_IN_USE,409,문제 회차에서 사용 중인 태그입니다.",
            "PROBLEM_VERSION_CONFLICT,409,다른 요청으로 변경되었습니다. 다시 조회해 주세요."
        }
    )
    void businessErrorsKeepTheirContract(String code, int httpStatus, String message) throws Exception {
        mvc
            .perform(
                get("/api/v1/test/business-error")
                    .param("code", code)
                    .header("X-User-Id", "12")
                    .header("X-User-Role", "USER")
            )
            .andExpect(status().is(httpStatus))
            .andExpect(jsonPath("$.success").value(false))
            .andExpect(jsonPath("$.error.code").value(code))
            .andExpect(jsonPath("$.error.message").value(message))
            .andExpect(jsonPath("$.error.details").isArray())
            .andExpect(jsonPath("$.error.details").isEmpty());
    }

    /**
     * 웹 응답 계약을 확인할 요청과 오류만 제공한다.
     */
    @RestController
    static class TestController {
        /**
         * 지정한 공통 또는 문제 오류를 발생시켜 예외 통합 이후의 응답을 확인한다.
         */
        @GetMapping("/api/v1/test/business-error")
        void businessError(@RequestParam String code) {
            ErrorCode errorCode
                = code.startsWith("PROBLEM_") ? ProblemErrorCode.valueOf(code) : CommonErrorCode.valueOf(code);
            throw new ProblemException(errorCode);
        }

        /**
         * 처리기 우선순위 검증을 위해 지정한 도메인 오류를 발생시킨다.
         */
        @GetMapping("/api/v1/test/domain-error")
        void domainError(@RequestParam String kind) {
            if ("lock-timeout".equals(kind)) {
                throw new CannotAcquireLockException("DB lock wait timeout");
            }
            if ("database-unavailable".equals(kind)) {
                throw new DataAccessResourceFailureException("DB connection unavailable");
            }
            if ("missing".equals(kind)) {
                throw new ProblemException(ProblemErrorCode.PROBLEM_TAG_NOT_FOUND);
            }
            if ("optimistic".equals(kind)) {
                throw new ObjectOptimisticLockingFailureException("Tag", 1L);
            }
            boolean duplicate = "duplicate".equals(kind);
            var sqlException = new SQLException("constraint", "23000", duplicate ? 1062 : 1451);
            var violation
                = new ConstraintViolationException(
                    "constraint",
                    sqlException,
                    "sql",
                    duplicate ? "tags.uk_tags_name" : "fk_problem_revision_tags_tag"
                );
            throw new DataIntegrityViolationException("constraint", violation);
        }

        /**
         * 인증 사용자와 추적 ID를 테스트 응답으로 반환한다.
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
         */
        @GetMapping("/internal/v1/test")
        Map<String, String> internal() {
            return Map.of("value", "internal");
        }

        /**
         * 요청 본문 검증 후 입력값을 그대로 응답한다.
         */
        @PostMapping("/api/v1/test")
        ApiResponse<Input> validate(@Valid @RequestBody Input input) {
            return ApiResponse.success(input);
        }

        /**
         * 예상하지 못한 서버 오류 응답을 검증하기 위해 예외를 발생시킨다.
         */
        @GetMapping("/api/v1/test/failure")
        void failure() {
            throw new IllegalStateException("secret-exception-body");
        }

        /**
         * 숫자 파라미터 변환을 검증하기 위해 입력값을 반환한다.
         */
        @GetMapping("/api/v1/test/number")
        int number(@RequestParam int value) {
            return value;
        }

        /**
         * 지정한 서버 오류 상태의 유지 여부를 검증하기 위해 예외를 발생시킨다.
         */
        @GetMapping("/api/v1/test/unavailable")
        void unavailable() {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "secret-exception-body");
        }

        /**
         * 본문 없는 응답 처리를 검증하기 위해 204 응답을 반환한다.
         */
        @GetMapping("/api/v1/test/empty")
        ResponseEntity<Void> empty() {
            return ResponseEntity.noContent().build();
        }

        /**
         * 바이너리 응답 처리를 검증하기 위해 고정 바이트를 반환한다.
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
     * 요청 본문 검증에 사용하는 테스트 입력.
     */
    record Input(@NotBlank String name) {
    }
}
