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

import com.backtoback.common.api.PageResponse;
import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.contest.exam.dto.ExamDetailResponse;
import com.backtoback.contest.exam.dto.ExamListResponse;
import com.backtoback.contest.exam.service.ExamQueryService;
import com.backtoback.contest.global.config.SecurityConfiguration;

/** 시험 목록과 상세의 인증 및 JSON 응답을 확인한다. */
@WebMvcTest(ExamQueryController.class)
@ContextConfiguration(
    classes = {
        ExamQueryController.class,
        SecurityConfiguration.class,
        GlobalExceptionHandler.class
    }
)
class ExamQueryControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ExamQueryService service;

    /** 기본 요청의 공통 페이지 형식과 문자열 시험 ID를 확인한다. */
    @Test
    @DisplayName("시험 목록은 공통 페이지 구조로 응답한다")
    void listResponse() throws Exception {
        // given
        var start = OffsetDateTime.parse("2026-11-07T10:00:00+09:00");
        var item = new ExamListResponse("55", "시험", "SCHEDULED", start, start.plusHours(1), 3, null);
        given(service.list("12", "7", null, 0, 20, "startsAt,desc"))
            .willReturn(new PageResponse<>(List.of(item), 0, 20, 1, 1, false));
        // when & then
        mvc
            .perform(get("/api/v1/studies/12/exams").header("X-User-Id", "7").header("X-User-Role", "USER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].examId").value("55"))
            .andExpect(jsonPath("$.data.content[0].myParticipantStatus").isEmpty())
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.hasNext").value(false));
    }

    /** 시작 전 상세에 문제 식별자나 본문이 없고 버전은 문자열인지 확인한다. */
    @Test
    @DisplayName("시작 전 상세는 문제 식별자 없이 설정과 버전을 반환한다")
    void detailResponse() throws Exception {
        // given
        var start = OffsetDateTime.parse("2026-11-07T10:00:00+09:00");
        var response
            = new ExamDetailResponse(
                "55",
                "12",
                "시험",
                "FIXED",
                "SCHEDULED",
                start,
                start.plusHours(1),
                start.minusHours(1),
                "100.00",
                3,
                "1",
                "0",
                "0",
                null
            );
        given(service.detail("55", "7")).willReturn(response);
        // when & then
        mvc
            .perform(get("/api/v1/exams/55").header("X-User-Id", "7").header("X-User-Role", "USER"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.examId").value("55"))
            .andExpect(jsonPath("$.data.totalScore").value("100.00"))
            .andExpect(jsonPath("$.data.examRevision").value("1"))
            .andExpect(jsonPath("$.data.problems").doesNotExist())
            .andExpect(jsonPath("$.data.myParticipation").isEmpty());
    }

    /** 두 경로 모두 Gateway 인증 없이 접근할 수 없는지 확인한다. */
    @Test
    @DisplayName("인증되지 않은 목록과 상세 요청을 거절한다")
    void requiresAuthentication() throws Exception {
        // when & then
        mvc.perform(get("/api/v1/studies/12/exams")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/v1/exams/55")).andExpect(status().isUnauthorized());
    }
}
