package com.backtoback.contest.exam.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.backtoback.contest.exam.service.ExamSubmissionHistoryService;
import com.backtoback.contest.global.config.SecurityConfiguration;

/** 시험 제출 이력의 응답 변환을 확인한다. */
@WebMvcTest(ExamSubmissionHistoryController.class)
@ContextConfiguration(
    classes = {
        ExamSubmissionHistoryController.class,
        SecurityConfiguration.class,
        GlobalExceptionHandler.class
    }
)
class ExamSubmissionHistoryControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ExamSubmissionHistoryService history;

    @Test
    @DisplayName("본인 제출 이력과 커서를 공통 응답으로 반환한다")
    void returnsHistory() throws Exception {
        var now = OffsetDateTime.parse("2026-10-20T21:00:00+09:00");
        given(history.read("55", "7", null, null, 20))
            .willReturn(
                new ExamSubmissionHistoryService.Page(
                    List
                        .of(
                            new ExamSubmissionHistoryService.Item(
                                "4001",
                                "101",
                                "1",
                                "MANUAL",
                                now,
                                "REQUESTED",
                                null,
                                null,
                                null,
                                null
                            )
                        ),
                    "cursor",
                    true
                )
            );
        mvc
            .perform(get("/api/v1/exams/55/submissions").header("X-User-Id", "7").header("X-User-Role", "USER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].examSubmissionId").value("4001"))
            .andExpect(jsonPath("$.data.nextCursor").value("cursor"))
            .andExpect(jsonPath("$.data.hasMore").value(true));
    }
}
