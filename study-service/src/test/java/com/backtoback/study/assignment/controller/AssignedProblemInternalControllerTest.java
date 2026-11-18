package com.backtoback.study.assignment.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.study.assignment.service.AssignedProblemQueryService;

/**
 * 5.7.4 배정 문제 레벨 잠금 예외 확인 내부 API의 요청 바인딩·응답 형식을 검증한다.
 * {@code JpaAuditingConfiguration} 때문에 {@code @WebMvcTest}는 JPA metamodel 오류로 실패해 standalone MockMvc로 작성한다.
 */
class AssignedProblemInternalControllerTest {

    private static final String URL = "/internal/v1/studies/members/{memberId}/assigned-problems/{problemId}";

    private MockMvc mockMvc;
    private AssignedProblemQueryService assignedProblemQueryService;

    @BeforeEach
    void setUp() {
        assignedProblemQueryService = mock(AssignedProblemQueryService.class);
        AssignedProblemInternalController controller
            = new AssignedProblemInternalController(assignedProblemQueryService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    @DisplayName("5.7.4 배정 문제이면 200과 감싸지 않은 assigned=true 응답을 반환한다")
    void getAssignedProblem_assigned_returns200WithRawBody() throws Exception {
        // given
        given(assignedProblemQueryService.isAssigned(7L, 101L)).willReturn(true);

        // when & then
        mockMvc
            .perform(get(URL, "7", "101"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.memberId").value("7"))
            .andExpect(jsonPath("$.problemId").value("101"))
            .andExpect(jsonPath("$.assigned").value(true))
            .andExpect(jsonPath("$.success").doesNotExist());
    }

    @Test
    @DisplayName("5.7.4 배정 문제가 아니거나 없는 문제이면 200과 assigned=false를 반환한다")
    void getAssignedProblem_notAssigned_returns200WithFalse() throws Exception {
        // given
        given(assignedProblemQueryService.isAssigned(7L, 999L)).willReturn(false);

        // when & then
        mockMvc
            .perform(get(URL, "7", "999"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.problemId").value("999"))
            .andExpect(jsonPath("$.assigned").value(false));
    }

    @Test
    @DisplayName("5.7.4 memberId나 problemId가 숫자가 아니면 판정하지 않고 400 COMMON_INVALID_REQUEST를 반환한다")
    void getAssignedProblem_nonNumericId_returns400() throws Exception {
        // when & then
        mockMvc
            .perform(get(URL, "abc", "101"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
        mockMvc
            .perform(get(URL, "7", "abc"))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
        verifyNoInteractions(assignedProblemQueryService);
    }
}
