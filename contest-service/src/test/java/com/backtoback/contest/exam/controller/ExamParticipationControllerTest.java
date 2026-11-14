package com.backtoback.contest.exam.controller;

import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.OffsetDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.contest.exam.dto.ExamParticipationResponse;
import com.backtoback.contest.exam.service.ExamParticipationService;
import com.backtoback.contest.exam.service.ExamParticipationService.Registration;
import com.backtoback.contest.global.config.SecurityConfiguration;

/** 등록 응답의 생성 여부와 인증 경계를 확인한다 (API 명세서 4.2.1). */
@WebMvcTest(ExamParticipationController.class)
@ContextConfiguration(
    classes = {
        ExamParticipationController.class,
        SecurityConfiguration.class,
        GlobalExceptionHandler.class
    }
)
class ExamParticipationControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ExamParticipationService service;

    /** 같은 응답 본문을 신규와 기존 참가에 사용해 HTTP 상태 차이를 확인한다. */
    @Test
    @DisplayName("신규 등록은 201, 기존 등록은 200과 같은 참가 ID를 반환한다")
    void registrationStatus() throws Exception {
        // given
        var time = OffsetDateTime.parse("2026-10-20T21:30:00+09:00");
        var response = new ExamParticipationResponse("411", "55", "REGISTERED", null, time, time, "1");
        given(service.register("55", "7"))
            .willReturn(new Registration(response, true), new Registration(response, false));
        // when & then
        for (int expected : new int[] {
            201,
            200
        }) {
            mvc
                .perform(post("/api/v1/exams/55/participants").header("X-User-Id", "7").header("X-User-Role", "USER"))
                .andExpect(status().is(expected))
                .andExpect(jsonPath("$.data.participantId").value("411"))
                .andExpect(jsonPath("$.data.examId").value("55"))
                .andExpect(jsonPath("$.data.participantRevision").value("1"));
        }
    }

    /** Gateway 인증 정보가 없는 요청의 접근 경계를 확인한다. */
    @Test
    @DisplayName("인증되지 않은 참가 등록 요청을 거절한다")
    void requiresAuthentication() throws Exception {
        // when & then
        mvc.perform(post("/api/v1/exams/55/participants")).andExpect(status().isUnauthorized());
    }
}
