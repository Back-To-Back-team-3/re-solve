package com.backtoback.contest.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.backtoback.contest.admin.dto.ExamRetryRequest;
import com.backtoback.contest.exam.dto.ExamSubmissionRequest;
import com.backtoback.contest.exam.service.ExamIntegrationTestSupport;
import com.backtoback.contest.exam.service.ExamJudgementService;
import com.backtoback.contest.global.error.ContestException;

/** 실패 접수와 관리자 작업의 잠금·회차 반영을 검증한다. */
class ExamRetryStoreIntegrationTest extends ExamIntegrationTestSupport {
    @Autowired
    private ExamRetryStore store;

    @Test
    @DisplayName("같은 작업은 재사용하고 다른 본문의 같은 작업 ID는 거절한다")
    void idempotencyAndConflict() {
        var receipt = failedReceipt();
        var request = new ExamRetryRequest("op-1", 1, "재처리");
        var first = store.prepare(Long.parseLong(examId), receipt, 7, request);
        var repeat = store.prepare(Long.parseLong(examId), receipt, 7, request);

        assertThat(first.state()).isEqualTo("PENDING");
        assertThat(repeat.id()).isEqualTo(first.id());
        assertThatThrownBy(
            () -> store.prepare(Long.parseLong(examId), receipt, 7, new ExamRetryRequest("op-1", 1, "다른 사유"))
        ).isInstanceOf(ContestException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contest_admin_operations", Long.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("목표 결과가 먼저 도착하면 늦은 재처리 응답은 판정을 유지한다")
    void resultBeforeResponse() {
        var submitted = submissions.submit(examId, "7", new ExamSubmissionRequest("101", "java", "코드", "1"));
        long receipt = Long.parseLong(submitted.examSubmissionId());
        var failed
            = new ExamJudgementService.Result(
                Long.parseLong(examId),
                receipt,
                9001,
                7,
                101,
                1001,
                1,
                "FAILED",
                null,
                null,
                null,
                submitted.receivedAt()
            );
        transactions.executeWithoutResult(status -> judgements.apply(failed));
        store.prepare(Long.parseLong(examId), receipt, 7, new ExamRetryRequest("op-2", 1, "재처리"));
        var judged
            = new ExamJudgementService.Result(
                Long.parseLong(examId),
                receipt,
                9001,
                7,
                101,
                1001,
                2,
                "JUDGED",
                "AC",
                3,
                3,
                submitted.receivedAt()
            );
        transactions.executeWithoutResult(status -> judgements.apply(judged));
        var closedAt = LocalDateTime.now(com.backtoback.common.time.ClockConfiguration.SERVICE_ZONE);
        jdbc
            .update(
                "UPDATE exam_participants SET status='FINISHED',finished_at=?,auto_submitted_at=? WHERE id=?",
                closedAt,
                closedAt,
                participantId
            );
        jdbc.update("UPDATE exams SET status='CLOSED',closed_at=? WHERE id=?", closedAt, examId);

        // 선도착 판정을 반영했어도 원격 작업이 미종결이면 확정할 수 없다.
        assertThat(store.require("op-2").state()).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM exam_submissions WHERE id=?", String.class, receipt))
            .isEqualTo("JUDGED");
        assertThat(finalization.finalizeExam(Long.parseLong(examId))).isFalse();

        var operation = store.succeeded("op-2", 2);

        assertThat(operation.state()).isEqualTo("SUCCEEDED");
        assertThat(operation.targetAttempt()).isEqualTo(2);
        assertThat(finalization.finalizeExam(Long.parseLong(examId))).isTrue();
        assertThat(jdbc.queryForObject("SELECT status FROM exam_submissions WHERE id=?", String.class, receipt))
            .isEqualTo("JUDGED");
        assertThat(jdbc.queryForObject("SELECT judge_attempt FROM exam_submissions WHERE id=?", Integer.class, receipt))
            .isEqualTo(2);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM audit_logs WHERE action='EXAM_RETRY' AND target_id=?",
                    Long.class,
                    Long.toString(receipt)
                )
        ).isEqualTo(1);
    }

    @Test
    @DisplayName("다른 시험의 접수는 재처리 작업으로 저장하지 않는다")
    void receiptMustBelongToExam() {
        long receipt = failedReceipt();
        jdbc
            .update(
                "INSERT INTO exams (study_id,creator_id,title,mode,starts_at,ends_at,status,exam_revision,"
                    + "total_score,result_revision,version,created_at,updated_at) "
                    + "SELECT study_id,creator_id,title,mode,starts_at,ends_at,status,exam_revision,total_score,"
                    + "result_revision,version,created_at,updated_at FROM exams WHERE id=?",
                examId
            );
        long otherExam = jdbc.queryForObject("SELECT MAX(id) FROM exams", Long.class);
        assertThatThrownBy(() -> store.prepare(otherExam, receipt, 7, new ExamRetryRequest("op-3", 1, "재처리")))
            .isInstanceOf(ContestException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM contest_admin_operations", Long.class)).isZero();
    }

    @Test
    @DisplayName("시험 확정과 재처리가 경합하면 둘 중 하나만 성공한다")
    void finalizationAndRetrySerialize() throws Exception {
        long receipt = failedReceipt();
        var now = LocalDateTime.now(com.backtoback.common.time.ClockConfiguration.SERVICE_ZONE);
        jdbc.update("UPDATE exam_submissions SET is_zero_confirmed=true WHERE id=?", receipt);
        jdbc
            .update(
                "UPDATE exam_participants SET status='FINISHED',finished_at=?,auto_submitted_at=? WHERE id=?",
                now,
                now,
                participantId
            );
        jdbc.update("UPDATE exams SET status='CLOSED',closed_at=? WHERE id=?", now, examId);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var retry = pool.submit(() -> {
                start.await();
                try {
                    store.prepare(Long.parseLong(examId), receipt, 7, new ExamRetryRequest("race-op", 1, "재처리"));
                    return true;
                } catch (ContestException rejected) {
                    return false;
                }
            });
            var finalize = pool.submit(() -> {
                start.await();
                return finalization.finalizeExam(Long.parseLong(examId));
            });
            start.countDown();
            assertThat(retry.get(10, TimeUnit.SECONDS)).isNotEqualTo(finalize.get(10, TimeUnit.SECONDS));
        }
    }

    private long failedReceipt() {
        var submitted = submissions.submit(examId, "7", new ExamSubmissionRequest("101", "java", "코드", "1"));
        long receipt = Long.parseLong(submitted.examSubmissionId());
        transactions
            .executeWithoutResult(
                status -> judgements
                    .apply(
                        new ExamJudgementService.Result(
                            Long.parseLong(examId),
                            receipt,
                            9001,
                            7,
                            101,
                            1001,
                            1,
                            "FAILED",
                            null,
                            null,
                            null,
                            submitted.receivedAt()
                        )
                    )
            );
        return receipt;
    }
}
