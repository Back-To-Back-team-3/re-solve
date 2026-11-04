package com.backtoback.contest.exam.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.contest.exam.domain.Exam;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.exam.service.StudyExamQueryService;
import com.backtoback.contest.global.config.SecurityConfiguration;

import tools.jackson.databind.ObjectMapper;

/** 실제 조회의 내부 HTTP 응답과 입력 오류를 확인한다. (API 명세서 §4.7.5) */
@WebMvcTest(StudyExamQueryController.class)
@ContextConfiguration(
    classes = {
        StudyExamQueryController.class,
        StudyExamQueryService.class,
        SecurityConfiguration.class,
        GlobalExceptionHandler.class
    }
)
@TestPropertySource(properties = "contest.internal-http.mode=real")
class StudyExamQueryControllerTest {
    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private ExamRepository exams;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("인증 헤더 없이 빈 목록과 명시적인 마지막 커서를 반환한다")
    void returnsEmptyPage() throws Exception {
        given(exams.findByStudyIdAndIdGreaterThanOrderByIdAsc(eq(12L), eq(0L), any())).willReturn(List.of());
        mvc
            .perform(get("/internal/v1/contest/studies/12/exams"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items").isEmpty())
            .andExpect(jsonPath("$.hasMore").value(false))
            .andExpect(jsonPath("$.nextCursor").value((Object)null));
    }

    @Test
    @DisplayName("시험 항목과 커서를 반환하고 다음 요청에서 확정 상태를 구분한다")
    void returnsItemsAcrossPages() throws Exception {
        var closed = mock(Exam.class);
        var finalized = mock(Exam.class);
        var start = LocalDateTime.parse("2026-01-01T09:00:00");
        given(closed.getId()).willReturn(51L);
        given(closed.getStatus()).willReturn(ExamStatus.CLOSED);
        given(closed.getStartsAt()).willReturn(start);
        given(closed.getEndsAt()).willReturn(start.plusHours(1));
        given(closed.getExamRevision()).willReturn(2);
        given(finalized.getId()).willReturn(52L);
        given(finalized.getStatus()).willReturn(ExamStatus.FINALIZED);
        given(finalized.getStartsAt()).willReturn(start.plusDays(1));
        given(finalized.getEndsAt()).willReturn(start.plusDays(1).plusHours(1));
        given(finalized.getExamRevision()).willReturn(3);
        given(exams.findByStudyIdAndIdGreaterThanOrderByIdAsc(eq(12L), eq(0L), any()))
            .willReturn(List.of(closed, finalized));
        given(exams.findByStudyIdAndIdGreaterThanOrderByIdAsc(eq(12L), eq(51L), any())).willReturn(List.of(finalized));

        var response
            = mvc
                .perform(get("/internal/v1/contest/studies/12/exams").param("size", "1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].examId").value("51"))
                .andExpect(jsonPath("$.items[0].examId").isString())
                .andExpect(jsonPath("$.items[0].examRevision").value("2"))
                .andExpect(jsonPath("$.items[0].examRevision").isString())
                .andExpect(jsonPath("$.items[0].status").value("CLOSED"))
                .andExpect(jsonPath("$.items[0].startsAt").value("2026-01-01T09:00:00+09:00"))
                .andExpect(jsonPath("$.items[0].endsAt").value("2026-01-01T10:00:00+09:00"))
                .andExpect(jsonPath("$.hasMore").value(true))
                .andExpect(jsonPath("$.nextCursor").isString())
                .andReturn()
                .getResponse();
        String cursor = objectMapper.readTree(response.getContentAsString()).path("nextCursor").asString();
        mvc
            .perform(get("/internal/v1/contest/studies/12/exams").param("size", "1").param("after", cursor))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.items[0].examId").value("52"))
            .andExpect(jsonPath("$.items[0].examId").isString())
            .andExpect(jsonPath("$.items[0].examRevision").value("3"))
            .andExpect(jsonPath("$.items[0].examRevision").isString())
            .andExpect(jsonPath("$.items[0].status").value("FINALIZED"))
            .andExpect(jsonPath("$.items[0].startsAt").value("2026-01-02T09:00:00+09:00"))
            .andExpect(jsonPath("$.items[0].endsAt").value("2026-01-02T10:00:00+09:00"))
            .andExpect(jsonPath("$.hasMore").value(false))
            .andExpect(jsonPath("$.nextCursor").value((Object)null));
        mvc
            .perform(get("/internal/v1/contest/studies/13/exams").param("after", cursor))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    @Test
    @DisplayName("빈 커서와 BIGINT 범위를 넘는 커서를 공통 오류로 거절한다")
    void rejectsInvalidCursorBoundaries() throws Exception {
        String overflow
            = Base64
                .getUrlEncoder()
                .withoutPadding()
                .encodeToString("study:12:9223372036854775808".getBytes(StandardCharsets.UTF_8));
        for (String cursor : List.of("", overflow)) {
            mvc
                .perform(get("/internal/v1/contest/studies/12/exams").param("after", cursor))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
        }
    }

    @Test
    @DisplayName("잘못된 식별자와 커서 및 페이지 크기를 공통 오류로 거절한다")
    void rejectsInvalidInputs() throws Exception {
        for (String path : List
            .of(
                "/abc/exams",
                "/0/exams",
                "/12/exams?after=invalid!",
                "/12/exams?size=0",
                "/12/exams?size=101",
                "/12/exams?size=abc"
            )) {
            mvc
                .perform(get("/internal/v1/contest/studies" + path))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
        }
    }
}
