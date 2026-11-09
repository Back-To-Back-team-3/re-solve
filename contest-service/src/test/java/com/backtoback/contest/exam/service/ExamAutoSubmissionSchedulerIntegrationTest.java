package com.backtoback.contest.exam.service;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.common.time.ClockConfiguration;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/** 반복 실패가 있는 자동 제출 후보의 페이지 순회를 확인한다. */
class ExamAutoSubmissionSchedulerIntegrationTest extends ExamIntegrationTestSupport {
    @Test
    @DisplayName("자동 제출 첫 100명이 실패해도 다음 참가자를 처리하고 실패 후보를 재시도한다")
    void continuesAfterFailedPage() {
        String job = "ExamAutoSubmissionScheduler";
        schedulerCursors.clear(job);
        var deadline = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).minusMinutes(1);
        jdbc.update("UPDATE exam_participants SET personal_ends_at=? WHERE id=?", deadline, participantId);
        for (int number = 1; number <= 100; number++) {
            jdbc.update("""
                INSERT INTO exam_participants (exam_id,member_id,status,participant_revision,registered_at,
                    entered_at,personal_ends_at,total_score,is_ranked,version,created_at,updated_at)
                SELECT exam_id,?,'STARTED',participant_revision,registered_at,entered_at,
                    personal_ends_at,0,false,0,created_at,updated_at FROM exam_participants WHERE id=?
                """, 1000 + number, participantId);
        }
        long lastId = jdbc.queryForObject("SELECT MAX(id) FROM exam_participants", Long.class);
        var finisher = mock(ExamAutoSubmissionService.class);
        given(finisher.finishDue(anyLong(), anyLong(), anyLong())).willThrow(new IllegalStateException("failure"));
        var scheduler
            = new ExamAutoSubmissionScheduler(
                jdbc,
                finisher,
                Clock.systemUTC(),
                new SimpleMeterRegistry(),
                schedulerCursors
            );
        try {
            scheduler.run();
            scheduler.run();
            verify(finisher).finishDue(Long.parseLong(examId), 1100, lastId);
            scheduler.run();
            verify(finisher, times(2)).finishDue(Long.parseLong(examId), 7, participantId);
        } finally {
            schedulerCursors.clear(job);
        }
    }
}
