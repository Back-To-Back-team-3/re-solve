package com.backtoback.contest.exam.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.contest.exam.service.ExamAutoSubmissionService;
import com.backtoback.contest.global.config.SecurityConfiguration;

/** 개인 시험 종료의 응답 변환을 확인한다. */
@WebMvcTest(ExamFinishController.class)
@ContextConfiguration(
    classes = {
        ExamFinishController.class,
        SecurityConfiguration.class,
        GlobalExceptionHandler.class
    }
)
class ExamFinishControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ExamAutoSubmissionService submissions;

    @Test
    @DisplayName("시험 종료와 자동 접수 결과를 공통 응답으로 반환한다")
    void returnsFinishResult() throws Exception {
        var now = OffsetDateTime.parse("2026-10-20T21:00:00+09:00");
        given(submissions.finish("55", "7"))
            .willReturn(new ExamAutoSubmissionService.Response("411", "FINISHED", now, now, List.of("4001")));
        mvc
            .perform(post("/api/v1/exams/55/finish").header("X-User-Id", "7").header("X-User-Role", "USER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.participantId").value("411"))
            .andExpect(jsonPath("$.data.autoSubmissionIds[0]").value("4001"));
    }
}
