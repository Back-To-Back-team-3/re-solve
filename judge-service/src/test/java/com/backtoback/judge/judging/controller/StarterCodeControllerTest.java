package com.backtoback.judge.judging.controller;

import static org.assertj.core.api.Assertions.assertThat;
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
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.judge.global.security.SecurityConfiguration;
import com.backtoback.judge.judging.service.StarterCodeService;
import com.backtoback.judge.judging.service.execution.cpp.CppExecutionCodeGenerator;
import com.backtoback.judge.judging.service.execution.java.JavaExecutionCodeGenerator;
import com.backtoback.judge.judging.service.execution.python.PythonExecutionCodeGenerator;

import tools.jackson.databind.json.JsonMapper;

/** 실제 언어 생성기를 연결해 내부 기본 코드 API 계약을 검증한다. */
@WebMvcTest(StarterCodeController.class)
@Import(
    {
        SecurityConfiguration.class,
        GlobalExceptionHandler.class,
        StarterCodeService.class,
        JavaExecutionCodeGenerator.class,
        PythonExecutionCodeGenerator.class,
        CppExecutionCodeGenerator.class
    }
)
class StarterCodeControllerTest {
    private static final String SPEC = """
        {"name":"solution","parameters":[{"name":"value","type":{"kind":"INT32"}}],
         "returnType":{"kind":"INT32"}}
        """;
    @Autowired
    private MockMvc mvc;

    /**
     * 요청한 언어 순서로 실제 생성 결과를 반환한다.
     *
     * @throws Exception 요청 검증 실패
     */
    @Test
    @DisplayName("토큰 없이 세 언어의 기본 코드를 요청 순서로 생성하고 래퍼 없이 반환한다")
    void generatesSupportedLanguages() throws Exception {
        var result
            = mvc
                .perform(
                    post("/internal/v1/judge/starter-codes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"functionSpec\":" + SPEC + ",\"languages\":[\"cpp\",\"java\",\"python3\"]}")
                )
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.starterCodes.length()").value(3))
                .andExpect(jsonPath("$.starterCodes[0].language").value("cpp"))
                .andExpect(jsonPath("$.starterCodes[1].language").value("java"))
                .andExpect(jsonPath("$.starterCodes[2].language").value("python3"))
                .andReturn();
        var codes
            = JsonMapper.builder().build().readTree(result.getResponse().getContentAsString()).get("starterCodes");
        for (var code : codes) {
            assertThat(code.get("sourceCode").asString()).contains("solution").contains("value");
        }
    }

    /**
     * 중복·누락·잘못된 언어와 함수 구조를 입력 오류로 처리한다.
     *
     * @param body 잘못된 요청
     * @throws Exception 요청 검증 실패
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "{\"functionSpec\":null,\"languages\":[\"java\"]}",
            "{\"functionSpec\":{},\"languages\":[\"java\"]}",
            "{\"functionSpec\":{\"name\":\"solution\",\"parameters\":[],"
                + "\"returnType\":{\"kind\":\"INT32\"}},\"languages\":[\"java\",\"java\"]}",
            "{\"functionSpec\":{},\"languages\":[\"rust\"]}",
            "{\"functionSpec\":{},\"languages\":null}"
        }
    )
    @DisplayName("잘못된 함수 명세와 언어는 공통 입력 오류로 거부한다")
    void rejectsInvalidRequest(String body) throws Exception {
        mvc
            .perform(post("/internal/v1/judge/starter-codes").contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }
}
