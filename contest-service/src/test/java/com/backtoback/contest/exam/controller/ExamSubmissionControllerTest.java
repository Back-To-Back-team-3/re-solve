package com.backtoback.contest.exam.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.contest.exam.service.ExamSubmissionService;
import com.backtoback.contest.global.config.SecurityConfiguration;

/** 직접 제출의 입력 검증과 응답 형식을 확인한다. */
@WebMvcTest(ExamSubmissionController.class)
@ContextConfiguration(
    classes = {
        ExamSubmissionController.class,
        SecurityConfiguration.class,
        GlobalExceptionHandler.class
    }
)
class ExamSubmissionControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ExamSubmissionService submissions;

    @Test
    @DisplayName("필수 요청값이 없으면 서비스를 호출하지 않고 요청 오류를 반환한다")
    void rejectsMissingRequiredField() throws Exception {
        mvc
            .perform(
                post("/api/v1/exams/55/submissions")
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"problemId\":\"101\",\"language\":\"java\",\"seq\":\"1\"}")
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
        verify(submissions, never()).submit(any(), any(), any());
    }

    @Test
    @DisplayName("빈 코드는 컨트롤러가 선행 거절하지 않고 접수 결과를 반환한다")
    void passesNonNullCodeToService() throws Exception {
        var now = OffsetDateTime.parse("2026-10-20T21:00:00+09:00");
        given(submissions.submit(eq("55"), eq("7"), any()))
            .willReturn(new ExamSubmissionService.Response("4001", "ACCEPTED", now, "1", now));
        mvc
            .perform(
                post("/api/v1/exams/55/submissions")
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "USER")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"problemId\":\"101\",\"language\":\"java\",\"sourceCode\":\"\",\"seq\":\"1\"}")
            )
            .andExpect(status().isAccepted())
            .andExpect(jsonPath("$.data.examSubmissionId").value("4001"))
            .andExpect(jsonPath("$.data.draftSeq").value("1"));
        verify(submissions).submit(eq("55"), eq("7"), any());
    }
}
