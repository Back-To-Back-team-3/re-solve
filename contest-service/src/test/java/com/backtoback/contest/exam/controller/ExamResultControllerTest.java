package com.backtoback.contest.exam.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.contest.exam.service.ExamResultQueryService;
import com.backtoback.contest.global.config.SecurityConfiguration;

/** 결과 DTO 변환 뒤에도 명세의 JSON 필드가 유지되는지 확인한다. */
@WebMvcTest(ExamResultController.class)
@ContextConfiguration(
    classes = {
        ExamResultController.class,
        SecurityConfiguration.class,
        GlobalExceptionHandler.class
    }
)
class ExamResultControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ExamResultQueryService results;

    @Test
    @DisplayName("본인 결과의 진행률과 문제별 결과를 공통 응답으로 반환한다")
    void returnsMyResult() throws Exception {
        given(results.me("55", "7"))
            .willReturn(
                new ExamResultQueryService.Me(
                    "PROVISIONAL",
                    "3",
                    "50.00",
                    1,
                    true,
                    new ExamResultQueryService.Progress(1, 2),
                    List.of(new ExamResultQueryService.Problem("101", "4001", "AC", "50.00", 2, 2, false))
                )
            );
        mvc
            .perform(get("/api/v1/exams/55/results/me").header("X-User-Id", "7").header("X-User-Role", "USER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.isRanked").value(true))
            .andExpect(jsonPath("$.data.progress.terminalCount").value(1))
            .andExpect(jsonPath("$.data.problems[0].lastValidExamSubmissionId").value("4001"));
    }

    @Test
    @DisplayName("시험 순위의 커서와 항목을 공통 응답으로 반환한다")
    void returnsStandings() throws Exception {
        given(results.standings("55", "7", null, 20))
            .willReturn(
                new ExamResultQueryService.Page(
                    "FINALIZED",
                    "4",
                    List.of(new ExamResultQueryService.StandingItem("7", "회원", "50.00", 1, false)),
                    "cursor",
                    true
                )
            );
        mvc
            .perform(get("/api/v1/exams/55/results").header("X-User-Id", "7").header("X-User-Role", "USER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].memberId").value("7"))
            .andExpect(jsonPath("$.data.nextCursor").value("cursor"))
            .andExpect(jsonPath("$.data.hasMore").value(true));
    }
}
