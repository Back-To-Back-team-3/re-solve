package com.backtoback.contest.exam.service;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.store.ExamSchedulerCursorStore;

/** 스케줄러의 페이지 순회를 검증한다. */
class ExamResultFinalizationSchedulerIntegrationTest extends ExamIntegrationTestSupport {
    @Test
    @DisplayName("시험 확정 스케줄러는 보류된 앞 페이지와 재시작 위치를 넘어 순회한다")
    void finalizationSchedulerContinuesAcrossPagesAndResume() {
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc.update("UPDATE exams SET status='CLOSED',closed_at=? WHERE id=?", now, examId);
        for (int i = 0; i < 100; i++) {
            jdbc
                .update(
                    "INSERT INTO exams (study_id,creator_id,title,mode,starts_at,ends_at,status,exam_revision,"
                        + "total_score,result_revision,version,closed_at,created_at,updated_at) "
                        + "VALUES (12,7,'시험','FIXED',?,?,'CLOSED',1,100,0,0,?,?,?)",
                    now,
                    now,
                    now,
                    now,
                    now
                );
        }
        var ids = jdbc.queryForList("SELECT id FROM exams ORDER BY closed_at,id", Long.class);
        var finalizer = org.mockito.Mockito.mock(ExamFinalizationService.class);
        var scheduler
            = new ExamResultFinalizationScheduler(
                jdbc,
                finalizer,
                java.time.Clock.systemUTC(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                schedulerCursors
            );
        String job = "ExamResultFinalizationScheduler";
        schedulerCursors.save(job, new ExamSchedulerCursorStore.Cursor(now, ids.get(98)));
        scheduler.run();
        verify(finalizer, times(1)).finalizeExam(ids.get(99));
        verify(finalizer, times(1)).finalizeExam(ids.get(100));
        org.mockito.Mockito.reset(finalizer);
        scheduler.run();
        verify(finalizer, times(101)).finalizeExam(org.mockito.ArgumentMatchers.anyLong());
        schedulerCursors.clear(job);
    }

}
