package com.backtoback.contest.exam.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
import com.backtoback.contest.exam.service.ExamEventSnapshotService;
import com.backtoback.contest.exam.service.ExamEventStreamService;
import com.backtoback.contest.global.config.SecurityConfiguration;

/** 공유 버전 ID를 사용하는 시험 SSE의 재연결 내용을 확인한다. */
@WebMvcTest(ExamEventController.class)
@ContextConfiguration(
    classes = {
        ExamEventController.class,
        ExamEventStreamService.class,
        SecurityConfiguration.class,
        GlobalExceptionHandler.class
    }
)
class ExamEventReconnectTest {
    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private ExamEventSnapshotService snapshots;

    @Test
    @DisplayName("연결 후 결과 버전이 바뀌면 같은 SSE 연결에 새 결과를 전달한다")
    void sendsChangedRevisionOnOpenConnection() throws Exception {
        var initial = new ExamEventSnapshotService.Snapshot("55", "CLOSED", 3, OffsetDateTime.now(), 1, 2);
        var changed = new ExamEventSnapshotService.Snapshot("55", "CLOSED", 4, OffsetDateTime.now(), 2, 2);
        given(snapshots.read("55", "7")).willReturn(initial, changed);
        var result
            = mvc
                .perform(get("/api/v1/exams/55/events").header("X-User-Id", "7").header("X-User-Role", "USER"))
                .andReturn();
        try {
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(4);
            while (!result.getResponse().getContentAsString().contains("id:EXAM:55:4")
                && System.nanoTime() < deadline) {
                java.util.concurrent.TimeUnit.MILLISECONDS.sleep(25);
            }
            assertThat(result.getResponse().getContentAsString())
                .contains("id:EXAM:55:4", "event:exam-result-updated", "\"resultRevision\":\"4\"");
        } finally {
            result.getRequest().getAsyncContext().complete();
        }
    }

    /** 같은 ID를 받았어도 상태와 결과 중 마지막으로 수신한 종류는 알 수 없다. */
    @Test
    @DisplayName("같은 버전 재연결에도 현재 상태와 결과를 모두 복구한다")
    void resendsBothEventsForSameVersion() throws Exception {
        given(snapshots.read("55", "7"))
            .willReturn(new ExamEventSnapshotService.Snapshot("55", "CLOSED", 3, OffsetDateTime.now(), 1, 2));
        var result
            = mvc
                .perform(
                    get("/api/v1/exams/55/events")
                        .header("X-User-Id", "7")
                        .header("X-User-Role", "USER")
                        .header("Last-Event-ID", "EXAM:55:3")
                )
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("event:exam-state-updated")))
                .andExpect(content().string(containsString("event:exam-result-updated")))
                .andExpect(content().string(containsString("id:EXAM:55:3")))
                .andReturn();
        result.getRequest().getAsyncContext().complete();
    }
}
