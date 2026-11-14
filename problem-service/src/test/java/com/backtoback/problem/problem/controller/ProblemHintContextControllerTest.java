package com.backtoback.problem.problem.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.problem.global.config.SecurityConfiguration;
import com.backtoback.problem.global.exception.ProblemExceptionHandler;
import com.backtoback.problem.problem.service.ProblemHintContextService;

/** 내부 힌트 문맥의 Study 필드·양수 식별자·실제 구현 활성화 조건을 확인한다. */
@WebMvcTest(
    controllers = ProblemHintContextController.class,
    properties = {
        "problem.internal-api.enabled=true",
        "problem.internal-api.implementation=real"
    }
)
@Import(
    {
        SecurityConfiguration.class,
        GlobalExceptionHandler.class,
        ProblemExceptionHandler.class
    }
)
class ProblemHintContextControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ProblemHintContextService service;

    /**
     * 내부 호출에 사용자 토큰을 요구하지 않고 Study가 읽을 필드와 문자열 ID를 제공한다.
     *
     * @throws Exception MVC 요청 실패
     */
    @Test
    @DisplayName("내부 힌트 응답은 래퍼 없이 Study 필드명과 문자열 식별자를 제공한다")
    void respondsWithStudyFields() throws Exception {
        given(service.read(1))
            .willReturn(new ProblemHintContextService.Context(1, 2L, "제목", "본문", List.of("그래프"), true));
        mvc
            .perform(get("/internal/v1/problems/1/hint-context"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.problemId").value("1"))
            .andExpect(jsonPath("$.problemRevisionId").value("2"))
            .andExpect(jsonPath("$.problemTitle").value("제목"))
            .andExpect(jsonPath("$.problemBody").value("본문"))
            .andExpect(jsonPath("$.problemTags[0]").value("그래프"))
            .andExpect(jsonPath("$.isHintAvailable").value(true))
            .andExpect(jsonPath("$.data").doesNotExist())
            .andExpect(jsonPath("$.title").doesNotExist());
    }

    /**
     * 비가용 입력의 {@code null}과 빈 태그를 HTTP 응답에서 유지한다.
     *
     * @throws Exception MVC 요청 실패
     */
    @Test
    @DisplayName("비가용 문제는 false·명시적 null 입력·빈 태그를 반환한다")
    void returnsUnavailableShape() throws Exception {
        given(service.read(1)).willReturn(new ProblemHintContextService.Context(1, null, null, null, List.of(), false));
        mvc
            .perform(get("/internal/v1/problems/1/hint-context"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.isHintAvailable").value(false))
            .andExpect(jsonPath("$.problemRevisionId").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.problemTitle").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.problemBody").value(org.hamcrest.Matchers.nullValue()))
            .andExpect(jsonPath("$.problemTags").isEmpty());
    }

    /**
     * 잘못된 식별자는 서비스 실행 전에 거절한다.
     *
     * @param id 양수 BIGINT가 아닌 식별자
     * @throws Exception MVC 요청 실패
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "0",
            "-1",
            "abc",
            "9223372036854775808"
        }
    )
    @DisplayName("양수 BIGINT가 아닌 문제 ID는 400으로 거절한다")
    void rejectsInvalidIds(String id) throws Exception {
        mvc.perform(get("/internal/v1/problems/" + id + "/hint-context")).andExpect(status().isBadRequest());
        verifyNoInteractions(service);
    }

    /** 실제 API 선택이 아니거나 Stub 프로필이면 경로를 등록하지 않는다. */
    @Test
    @DisplayName("힌트 문맥은 기존 enabled·real 조건에서만 활성화하고 Stub 프로필에서는 제외한다")
    void activationConditions() {
        var runner
            = new ApplicationContextRunner()
                .withBean(ProblemHintContextService.class, () -> mock(ProblemHintContextService.class))
                .withUserConfiguration(ProblemHintContextController.class);
        runner.run(context -> assertThat(context).doesNotHaveBean(ProblemHintContextController.class));
        runner
            .withPropertyValues("problem.internal-api.enabled=true", "problem.internal-api.implementation=real")
            .run(context -> assertThat(context).hasSingleBean(ProblemHintContextController.class));
        runner
            .withPropertyValues("problem.internal-api.enabled=false", "problem.internal-api.implementation=real")
            .run(context -> assertThat(context).doesNotHaveBean(ProblemHintContextController.class));
        runner
            .withPropertyValues(
                "spring.profiles.active=internal-stub",
                "problem.internal-api.enabled=true",
                "problem.internal-api.implementation=stub",
                "problem.internal-stub.enabled=true"
            )
            .run(context -> assertThat(context).doesNotHaveBean(ProblemHintContextController.class));
    }
}
