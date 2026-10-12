package com.backtoback.problem.global;

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

import com.backtoback.problem.global.api.ApiResponse;
import com.backtoback.problem.global.exception.GlobalExceptionHandler;
import com.backtoback.problem.global.logging.CorrelationIdFilter;
import com.backtoback.problem.global.security.SecurityConfiguration;
import com.backtoback.problem.global.security.user.GatewayUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;

@WebMvcTest
@Import(
    {
        ProblemWebTests.TestController.class,
        SecurityConfiguration.class,
        GlobalExceptionHandler.class,
        CorrelationIdFilter.class
    }
)
@ExtendWith(OutputCaptureExtension.class)
class ProblemWebTests {
    @Autowired
    private MockMvc mvc;

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

    @Test
    void ordinaryUserCannotCallAdminApi() throws Exception {
        mvc
            .perform(get("/api/v1/admin/test").header("X-User-Id", "12").header("X-User-Role", "USER"))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error.code").value("AUTH_ACCESS_DENIED"));
    }

    @Test
    void administratorCanCallAdminApi() throws Exception {
        mvc
            .perform(get("/api/v1/admin/test").header("X-User-Id", "12").header("X-User-Role", "ADMIN"))
            .andExpect(status().isOk());
    }

    @Test
    void incompleteGatewayIdentityIsRejected() throws Exception {
        mvc.perform(get("/api/v1/test").header("X-User-Id", "12")).andExpect(status().isUnauthorized());
        mvc
            .perform(get("/api/v1/test").header("X-User-Id", "12").header("X-User-Role", "SERVICE"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void internalRequestsAreAllowedWithoutAuthenticationAndReturnUnwrappedData() throws Exception {
        mvc
            .perform(get("/internal/v1/test"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.value").value("internal"))
            .andExpect(jsonPath("$.success").doesNotExist());
        mvc.perform(get("/internal/v1/test").header("Authorization", "Bearer unknown")).andExpect(status().isOk());
    }

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

    @Test
    void unsupportedMethodPreservesStatusAndAllowHeader() throws Exception {
        mvc
            .perform(put("/api/v1/test").header("X-User-Id", "12").header("X-User-Role", "USER"))
            .andExpect(status().isMethodNotAllowed())
            .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("GET")))
            .andExpect(header().string("Allow", org.hamcrest.Matchers.containsString("POST")))
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

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

    @Test
    void missingResourceRemainsNotFound() throws Exception {
        mvc
            .perform(get("/api/v1/test/missing").header("X-User-Id", "12").header("X-User-Role", "USER"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    @Test
    void explicitServerErrorPreservesStatusWithoutExposingReason() throws Exception {
        mvc
            .perform(get("/api/v1/test/unavailable").header("X-User-Id", "12").header("X-User-Role", "USER"))
            .andExpect(status().isServiceUnavailable())
            .andExpect(jsonPath("$.error.code").value("COMMON_INTERNAL_SERVER_ERROR"))
            .andExpect(jsonPath("$.error.message").value("서버 오류가 발생했습니다."));
    }

    @Test
    void unexpectedErrorDoesNotExposeExceptionMessage(CapturedOutput output) throws Exception {
        mvc
            .perform(get("/api/v1/test/failure").header("X-User-Id", "12").header("X-User-Role", "USER"))
            .andExpect(status().isInternalServerError())
            .andExpect(jsonPath("$.error.code").value("COMMON_INTERNAL_SERVER_ERROR"))
            .andExpect(jsonPath("$.error.message").value("서버 오류가 발생했습니다."));
        assertThat(output.getAll()).doesNotContain("secret-exception-body");
    }

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
        @GetMapping(
            {
                "/api/v1/test",
                "/api/v1/admin/test"
            }
        )
        ApiResponse<Map<String, String>> identity(@AuthenticationPrincipal GatewayUser user) {
            return ApiResponse.success(Map.of("userId", user.userId(), "correlationId", MDC.get("correlationId")));
        }

        @GetMapping("/internal/v1/test")
        Map<String, String> internal() {
            return Map.of("value", "internal");
        }

        @PostMapping("/api/v1/test")
        ApiResponse<Input> validate(@Valid @RequestBody Input input) {
            return ApiResponse.success(input);
        }

        @GetMapping("/api/v1/test/failure")
        void failure() {
            throw new IllegalStateException("secret-exception-body");
        }

        @GetMapping("/api/v1/test/number")
        int number(@RequestParam int value) {
            return value;
        }

        @GetMapping("/api/v1/test/unavailable")
        void unavailable() {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "secret-exception-body");
        }

        @GetMapping("/api/v1/test/empty")
        ResponseEntity<Void> empty() {
            return ResponseEntity.noContent().build();
        }

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
