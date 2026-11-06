package com.backtoback.problem.admin.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.problem.global.config.SecurityConfiguration;
import com.backtoback.problem.global.exception.ProblemExceptionHandler;
import com.backtoback.problem.problem.config.ProblemCreateWebConfiguration;
import com.backtoback.problem.problem.dto.request.ProblemCreateRequest;
import com.backtoback.problem.problem.dto.response.ProblemCreateResponse;
import com.backtoback.problem.problem.service.ProblemCreateBodyReader;
import com.backtoback.problem.problem.service.ProblemCreateService;

import tools.jackson.databind.ObjectMapper;

/** 실제 MVC 변환·Bean Validation·Gateway 권한 검사를 DB 없이 확인한다. */
@WebMvcTest(AdminProblemController.class)
@Import(
    {
        SecurityConfiguration.class,
        ProblemCreateWebConfiguration.class,
        ProblemCreateBodyReader.class,
        GlobalExceptionHandler.class,
        ProblemExceptionHandler.class
    }
)
class AdminProblemControllerTest {
    private static final String BODY = """
        {"scope":"GENERAL","title":"문제","description":"설명","constraints":"제약","examples":"예시",
         "difficulty":1,"tagIds":[],"functionSpec":{"name":"solution",
         "parameters":[{"name":"value","type":{"kind":"INT32"}}],"returnType":{"kind":"INT32"}},
         "testCases":[{"isSample":true,"argumentTexts":{"value":"1"},"expectedReturnText":"2"},
                      {"isSample":false,"argumentTexts":{"value":"2"},"expectedReturnText":"4"}]}
        """;
    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper mapper;
    @MockitoBean
    private ProblemCreateService service;

    /**
     * 관리자 등록은 최초 식별자와 버전을 201로 반환한다.
     *
     * @throws Exception MVC 요청 실패
     */
    @Test
    @DisplayName("관리자 등록은 최초 식별자와 버전을 201로 반환한다")
    void create() throws Exception {
        var expectedBody = mapper.readValue(BODY, ProblemCreateRequest.class);
        String clientIp = "192.0.2.17";
        given(service.create(8004L, "create", expectedBody, clientIp))
            .willReturn(new ProblemCreateResponse("1", "2", 1, 1));
        mvc.perform(post("/api/v1/admin/problems").with(request -> {
            request.setRemoteAddr(clientIp);
            return request;
        })
            .header("X-User-Id", "8004")
            .header("X-User-Role", "ADMIN")
            .header("Idempotency-Key", "create")
            .contentType(MediaType.APPLICATION_JSON)
            .content(BODY)
        )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.problemId").value("1"))
            .andExpect(jsonPath("$.data.problemRevisionId").value("2"))
            .andExpect(jsonPath("$.data.revisionNumber").value(1))
            .andExpect(jsonPath("$.data.rowVersion").value(1));
        verify(service).create(8004L, "create", expectedBody, clientIp);
    }

    /**
     * 기본 필드·중첩 제한·알 수 없는 필드·소수 정수 입력을 서비스 호출 전에 거절한다.
     *
     * @param replacement 원래 제목 필드를 대체할 JSON 필드
     * @throws Exception MVC 요청 실패
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "\"title\":\" \"",
            "\"title\":null",
            "\"title\":\"문제\",\"executionLimits\":{\"outputLimitKb\":257}",
            "\"title\":\"문제\",\"executionLimits\":{\"memoryKb\":512001}",
            "\"title\":\"문제\",\"unknown\":true",
            "\"title\":\"문제\",\"executionLimits\":{\"timeMs\":1000.5}"
        }
    )
    @DisplayName("잘못된 요청은 서비스 호출 전에 400으로 거절한다")
    void invalidBody(String replacement) throws Exception {
        mvc
            .perform(
                post("/api/v1/admin/problems")
                    .header("X-User-Id", "8004")
                    .header("X-User-Role", "ADMIN")
                    .header("Idempotency-Key", "create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY.replace("\"title\":\"문제\"", replacement))
            )
            .andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    /**
     * 실제 HTTP 본문 8MiB 초과를 서비스 호출 전에 거절한다.
     *
     * @throws Exception MVC 요청 실패
     */
    @Test
    @DisplayName("HTTP 본문 8MiB 초과는 413으로 거절한다")
    void oversizedBody() throws Exception {
        mvc
            .perform(
                post("/api/v1/admin/problems")
                    .header("X-User-Id", "8004")
                    .header("X-User-Role", "ADMIN")
                    .header("Idempotency-Key", "create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(" ".repeat(8388609))
            )
            .andExpect(status().is(413));
        verifyNoInteractions(service);
    }

    /**
     * 일반 회원의 관리자 등록 호출을 거절한다.
     *
     * @throws Exception MVC 요청 실패
     */
    @Test
    @DisplayName("일반 회원은 관리자 등록 API를 호출할 수 없다")
    void forbidden() throws Exception {
        mvc
            .perform(
                post("/api/v1/admin/problems")
                    .header("X-User-Id", "8005")
                    .header("X-User-Role", "USER")
                    .header("Idempotency-Key", "create")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(BODY)
            )
            .andExpect(status().isForbidden());
        verifyNoInteractions(service);
    }
}
