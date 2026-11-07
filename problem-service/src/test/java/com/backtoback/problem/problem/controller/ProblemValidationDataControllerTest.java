package com.backtoback.problem.problem.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.problem.global.config.SecurityConfiguration;
import com.backtoback.problem.global.exception.ProblemExceptionHandler;
import com.backtoback.problem.problem.dto.response.ValidationDataResponse;
import com.backtoback.problem.problem.service.ProblemValidationDataService;

/** 실제 내부 조회 경로의 필수 헤더·사용자 인증 생략·응답 형태를 확인한다. */
@WebMvcTest(ProblemValidationDataController.class)
@Import(
    {
        SecurityConfiguration.class,
        CorrelationIdFilter.class,
        GlobalExceptionHandler.class,
        ProblemExceptionHandler.class
    }
)
class ProblemValidationDataControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ProblemValidationDataService data;

    /**
     * 내부 자료는 사용자 토큰 없이 데이터 객체를 직접 반환한다.
     *
     * @throws Exception MVC 요청 실패
     */
    @Test
    @DisplayName("내부 검증 자료는 토큰 없이 공통 래퍼를 제외하고 반환한다")
    void internalData() throws Exception {
        given(data.read(3L))
            .willReturn(new ValidationDataResponse("3", "2", "java", "hash", "code", Map.of(), null, List.of()));
        mvc
            .perform(get("/internal/v1/problems/validations/3/data").header("X-Correlation-Id", "request-219"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.validationId").value("3"))
            .andExpect(jsonPath("$.data").doesNotExist());
    }

    /**
     * 내부 조회의 필수 추적 헤더가 빠지면 거절한다.
     *
     * @throws Exception MVC 요청 실패
     */
    @Test
    @DisplayName("내부 검증 자료 요청에 추적 헤더가 없으면 400으로 거절한다")
    void missingCorrelation() throws Exception {
        mvc.perform(get("/internal/v1/problems/validations/3/data")).andExpect(status().isBadRequest());
        verifyNoInteractions(data);
    }

    /** 기존 기본 경로는 유지하고 명시적 비활성화 및 Stub 프로필에서는 실제 컨트롤러를 제외한다. */
    @Test
    @DisplayName("실제 검증 자료는 기본 활성화되지만 내부 API 비활성화와 Stub 프로필에서는 등록하지 않는다")
    void activationConditions() {
        var runner
            = new ApplicationContextRunner()
                .withBean(ProblemValidationDataService.class, () -> mock(ProblemValidationDataService.class))
                .withUserConfiguration(ProblemValidationDataController.class);
        runner.run(context -> assertThat(context).hasSingleBean(ProblemValidationDataController.class));
        runner
            .withPropertyValues("problem.internal-api.enabled=true")
            .run(context -> assertThat(context).hasSingleBean(ProblemValidationDataController.class));
        runner
            .withPropertyValues("problem.internal-api.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(ProblemValidationDataController.class));
        runner
            .withPropertyValues("spring.profiles.active=internal-stub", "problem.internal-api.enabled=true")
            .run(context -> assertThat(context).doesNotHaveBean(ProblemValidationDataController.class));
    }
}
