package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.AopTestUtils;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.dto.ExamSubmissionRequest;

/** 시험 기능의 저장 및 동시성 통합 검증이다. */
class ExamFinalizationServiceIntegrationTest extends ExamIntegrationTestSupport {
    @Test
    @DisplayName("PENDING 관리자 작업은 확정을 막고 확인된 FAILED 작업은 막지 않는다")
    void finalizationWaitsForUnconfirmedOperation() {
        // given
        autoSubmissions.finish(examId, "7");
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc.update("UPDATE exams SET status='CLOSED',closed_at=? WHERE id=?", now, examId);
        jdbc
            .update(
                "INSERT INTO result_snapshots(context,context_id,result_revision,payload,created_at) "
                    + "VALUES ('EXAM',?,0,'{}',?)",
                examId,
                now
            );
        jdbc
            .update(
                "INSERT INTO contest_admin_operations(operation_id,operation_type,context,context_id,request_hash,"
                    + "state,actor_member_id,reason,created_at,updated_at) "
                    + "VALUES ('pending-test','EXAM_RETRY','EXAM',?,?,'PENDING',7,'확인',?,?)",
                examId,
                "a".repeat(64),
                now,
                now
            );
        // when & then
        assertThat(finalization.finalizeExam(Long.parseLong(examId))).isFalse();
        jdbc.update("UPDATE contest_admin_operations SET state='FAILED' WHERE operation_id='pending-test'");
        assertThat(finalization.finalizeExam(Long.parseLong(examId))).isTrue();
        assertThat(finalization.finalizeExam(Long.parseLong(examId))).isFalse();
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM result_snapshots WHERE context_id=?", Integer.class, examId)
        ).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM exams WHERE id=?", String.class, examId))
            .isEqualTo("FINALIZED");
    }

    @Test
    @DisplayName("실패 접수는 0점 확정 전까지 시험 결과 확정을 보류한다")
    void finalizationRequiresFailedReceiptResolution() {
        // given
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        var receipt = submissions.submit(examId, "7", new ExamSubmissionRequest("101", "java", "코드", "1"));
        autoSubmissions.finish(examId, "7");
        transactions
            .executeWithoutResult(
                status -> judgements
                    .apply(
                        new ExamJudgementService.Result(
                            Long.parseLong(examId),
                            Long.parseLong(receipt.examSubmissionId()),
                            9001,
                            7,
                            101,
                            1001,
                            1,
                            "FAILED",
                            null,
                            null,
                            null,
                            receipt.receivedAt()
                        )
                    )
            );
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc.update("UPDATE exams SET status='CLOSED',closed_at=? WHERE id=?", now, examId);
        // when & then
        assertThat(finalization.finalizeExam(Long.parseLong(examId))).isFalse();
        jdbc.update("UPDATE exam_submissions SET is_zero_confirmed=true WHERE id=?", receipt.examSubmissionId());
        assertThat(finalization.finalizeExam(Long.parseLong(examId))).isTrue();
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM result_snapshots WHERE context_id=?", Integer.class, examId)
        ).isEqualTo(1);
    }

    @Test
    @DisplayName("확정 Outbox 실패는 시험 상태와 새 스냅샷을 롤백한다")
    void finalizationRollsBackWhenOutboxFails() {
        // given
        autoSubmissions.finish(examId, "7");
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc.update("UPDATE exams SET status='CLOSED',closed_at=? WHERE id=?", now, examId);
        OutboxEventWriter target = AopTestUtils.getUltimateTargetObject(outbox);
        doThrow(new IllegalStateException("outbox failure"))
            .when(target)
            .append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        // when & then
        assertThatThrownBy(() -> finalization.finalizeExam(Long.parseLong(examId)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("outbox failure");
        assertThat(jdbc.queryForObject("SELECT status FROM exams WHERE id=?", String.class, examId))
            .isEqualTo("CLOSED");
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM result_snapshots WHERE context_id=?", Integer.class, examId)
        ).isZero();
    }

    @Test
    @DisplayName("완전 동점은 공동 순위로 확정하고 스냅샷에 코드 원문을 넣지 않는다")
    void finalizationAssignsSharedRanks() {
        // given
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc
            .update(
                "UPDATE exam_participants SET status='FINISHED',auto_submitted_at=?,finished_at=? WHERE id=?",
                now,
                now,
                participantId
            );
        for (long member : new long[] {
            8,
            9
        }) {
            jdbc
                .update(
                    "INSERT INTO exam_participants (exam_id,member_id,status,participant_revision,registered_at,"
                        + "entered_at,personal_ends_at,finished_at,total_score,auto_submitted_at,is_ranked,version,"
                        + "created_at,updated_at) VALUES (?,?,'FINISHED',3,?,?,?,?,0,?,false,0,?,?)",
                    examId,
                    member,
                    now.minusHours(1),
                    now.minusMinutes(30),
                    now,
                    now,
                    now,
                    now,
                    now
                );
        }
        var ids
            = jdbc
                .queryForList(
                    "SELECT id FROM exam_participants WHERE exam_id=? ORDER BY member_id",
                    Long.class,
                    examId
                );
        for (int i = 0; i < ids.size(); i++) {
            var received = i == 2 ? now.minusSeconds(1) : now.minusSeconds(2);
            jdbc
                .update(
                    "INSERT INTO exam_submissions (exam_participant_id,exam_id,problem_id,draft_seq,language,"
                        + "source_code,submission_type,received_at,status,submission_id,judge_attempt,verdict,"
                        + "passed_count," + "total_count,score,is_zero_confirmed,created_at,updated_at) "
                        + "VALUES (?,?,101,1,'JAVA','비공개코드','MANUAL',?,'JUDGED',?,1,'AC',1,1,50,false,?,?)",
                    ids.get(i),
                    examId,
                    received,
                    9000 + i,
                    now,
                    now
                );
        }
        jdbc.update("UPDATE exams SET status='CLOSED',closed_at=? WHERE id=?", now, examId);
        // when
        assertThat(finalization.finalizeExam(Long.parseLong(examId))).isTrue();
        // then
        assertThat(
            jdbc
                .queryForList(
                    "SELECT final_rank FROM exam_participants WHERE exam_id=? ORDER BY member_id",
                    Integer.class,
                    examId
                )
        ).containsExactly(1, 1, 3);
        String snapshot
            = jdbc.queryForObject("SELECT payload FROM result_snapshots WHERE context_id=?", String.class, examId);
        assertThat(snapshot).contains("firstAcceptedAt", "submissionCount").doesNotContain("비공개코드");
    }

    @Test
    @DisplayName("동시 확정은 하나의 스냅샷과 확정 이벤트만 저장한다")
    void concurrentFinalizationCreatesOneSnapshot() throws Exception {
        // given
        autoSubmissions.finish(examId, "7");
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc.update("UPDATE exams SET status='CLOSED',closed_at=? WHERE id=?", now, examId);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                return finalization.finalizeExam(Long.parseLong(examId));
            });
            var second = pool.submit(() -> {
                start.await();
                return finalization.finalizeExam(Long.parseLong(examId));
            });
            // when
            start.countDown();
            var results = java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            // then
            assertThat(results).containsExactlyInAnyOrder(true, false);
        }
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM result_snapshots WHERE context_id=?", Integer.class, examId)
        ).isEqualTo(1);
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='ExamFinalized'", Integer.class)
        ).isEqualTo(1);
    }

}
