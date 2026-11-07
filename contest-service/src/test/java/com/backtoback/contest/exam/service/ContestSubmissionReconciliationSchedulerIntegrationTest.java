package com.backtoback.contest.exam.service;

import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.store.ExamSchedulerCursorStore;

/** 스케줄러의 페이지 순회를 검증한다. */
class ContestSubmissionReconciliationSchedulerIntegrationTest extends ExamIntegrationTestSupport {
    @Test
    @DisplayName("전달 전 크기 초과 실패는 Judge 조회와 요청 복구를 반복하지 않는다")
    void excludesPredispatchFailuresFromRemoteReconciliation() {
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).minusMinutes(2);
        jdbc
            .update(
                "INSERT INTO exam_submissions (exam_participant_id,exam_id,problem_id,draft_seq,language,"
                    + "source_code,submission_type,received_at,status,is_zero_confirmed,created_at,updated_at,"
                    + "dispatch_failure_code,judge_attempt) VALUES (?,?,101,1,'JAVA','기존 코드','AUTO',?,'FAILED',"
                    + "false,?,?,'CODE_TOO_LARGE',0)",
                participantId,
                examId,
                now,
                now,
                now
            );
        var client = org.mockito.Mockito.mock(com.backtoback.contest.exam.client.ExamReceiptClient.class);
        var reconcile = org.mockito.Mockito.mock(ExamReceiptReconciliation.class);
        var scheduler
            = new ContestSubmissionReconciliationScheduler(
                jdbc,
                client,
                reconcile,
                java.time.Clock.systemUTC(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                schedulerCursors
            );
        schedulerCursors.clear("ContestSubmissionReconciliationScheduler");

        scheduler.run();

        org.mockito.Mockito.verifyNoInteractions(client, reconcile);
    }

    @Test
    @DisplayName("접수 대사 스케줄러는 실패한 앞 페이지를 넘어 순회한다")
    void reconciliationSchedulerContinuesAcrossPagesAndResume() {
        var now
            = LocalDateTime
                .now(ClockConfiguration.SERVICE_ZONE)
                .minusMinutes(2)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        for (int i = 1; i <= 101; i++) {
            jdbc
                .update(
                    "INSERT INTO exam_submissions (exam_participant_id,exam_id,problem_id,draft_seq,language,"
                        + "source_code,submission_type,received_at,status,is_zero_confirmed,created_at,updated_at) "
                        + "VALUES (?,?,101,?,'JAVA','코드','MANUAL',?,'ACCEPTED',false,?,?)",
                    participantId,
                    examId,
                    i,
                    now,
                    now,
                    now
                );
        }
        var ids = jdbc.queryForList("SELECT id FROM exam_submissions ORDER BY received_at,id", Long.class);
        var client = org.mockito.Mockito.mock(com.backtoback.contest.exam.client.ExamReceiptClient.class);
        var reconcile = org.mockito.Mockito.mock(ExamReceiptReconciliation.class);
        org.mockito.Mockito
            .doThrow(new IllegalStateException("remote failure"))
            .when(client)
            .find(org.mockito.ArgumentMatchers.anyLong());
        var scheduler
            = new ContestSubmissionReconciliationScheduler(
                jdbc,
                client,
                reconcile,
                java.time.Clock.systemUTC(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                schedulerCursors
            );
        String job = "ContestSubmissionReconciliationScheduler";
        schedulerCursors.save(job, new ExamSchedulerCursorStore.Cursor(now, ids.get(98)));
        scheduler.run();
        verify(client, times(1)).find(ids.get(99));
        verify(client, times(1)).find(ids.get(100));
        org.mockito.Mockito.reset(client);
        scheduler.run();
        verify(client, times(101)).find(org.mockito.ArgumentMatchers.anyLong());
        schedulerCursors.clear(job);
    }

}
