package com.backtoback.contest.exam.controller;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.backtoback.common.web.exception.GlobalExceptionHandler;
import com.backtoback.contest.exam.service.ExamEventStreamService;
import com.backtoback.contest.global.config.SecurityConfiguration;

/** 시험 SSE의 인증과 재연결 헤더 전달을 확인한다. */
@WebMvcTest(ExamEventController.class)
@ContextConfiguration(
    classes = {
        ExamEventController.class,
        SecurityConfiguration.class,
        GlobalExceptionHandler.class
    }
)
class ExamEventControllerTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ExamEventStreamService streams;

    /** 재연결의 마지막 ID와 인증된 회원을 스트림 서비스에 전달한다. */
    @Test
    @DisplayName("재연결 헤더와 본인 식별자로 시험 SSE를 시작한다")
    void forwardsReconnectCursor() throws Exception {
        // given
        given(streams.subscribe("55", "7", "EXAM:55:3")).willReturn(new SseEmitter());
        // when & then
        mvc
            .perform(
                get("/api/v1/exams/55/events")
                    .header("X-User-Id", "7")
                    .header("X-User-Role", "USER")
                    .header("Last-Event-ID", "EXAM:55:3")
            )
            .andExpect(status().isOk())
            .andExpect(request().asyncStarted());
        verify(streams).subscribe("55", "7", "EXAM:55:3");
    }

    /** 인증이 없는 SSE 요청을 거절한다. */
    @Test
    @DisplayName("인증되지 않은 시험 SSE 요청은 거절한다")
    void rejectsAnonymous() throws Exception {
        // when & then
        mvc.perform(get("/api/v1/exams/55/events")).andExpect(status().isUnauthorized());
    }
}
