package com.backtoback.contest.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.AopTestUtils;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.common.web.security.user.UserRole;
import com.backtoback.contest.admin.dto.ExamRefinalizationRequest;
import com.backtoback.contest.admin.dto.ExamResolutionRequest;
import com.backtoback.contest.admin.dto.ExamRetryRequest;
import com.backtoback.contest.exam.service.ExamIntegrationTestSupport;
import com.backtoback.contest.global.error.ContestException;

/** 실패 접수 종결과 재확정의 DB 원자성·멱등성을 검증한다. */
class ExamResolutionServiceIntegrationTest extends ExamIntegrationTestSupport {
    @Autowired
    private ExamResolutionService service;
    @Autowired
    private ExamRetryStore retryStore;
    private final GatewayUser admin = new GatewayUser("7", UserRole.ADMIN);

    /** Judge에 전달되지 않은 실패는 원문과 실패 사유를 보존한 채 회차 0으로 종결한다. */
    @Test
    @DisplayName("전달 전 초과 Draft를 Judge 없이 0점 종결하고 동일 작업은 중복 처리하지 않는다")
    void resolvesUndispatchedFailure() {
        long receipt = failedReceipt(0, "CODE_TOO_LARGE");
        var request = new ExamResolutionRequest("zero-op", "ZERO_CONFIRM", 0, "장애 종결");
        var first = service.resolve("EXAM", Long.toString(receipt), admin, request);
        jdbc.update("UPDATE exams SET result_revision=result_revision+1 WHERE id=?", examId);
        var repeated = service.resolve("EXAM", Long.toString(receipt), admin, request);

        assertThat(first.resolved()).isTrue();
        assertThat(repeated.resultRevision()).isEqualTo(first.resultRevision());
        assertThat(jdbc.queryForObject("SELECT score FROM exam_submissions WHERE id=?", BigDecimal.class, receipt))
            .isEqualByComparingTo("0.00");
        assertThat(
            jdbc.queryForObject("SELECT dispatch_failure_code FROM exam_submissions WHERE id=?", String.class, receipt)
        ).isEqualTo("CODE_TOO_LARGE");
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM audit_logs WHERE action='EXAM_ZERO_CONFIRM' AND target_id=?",
                    Long.class,
                    Long.toString(receipt)
                )
        ).isEqualTo(1);
        assertThatThrownBy(
            () -> service
                .resolve(
                    "EXAM",
                    Long.toString(receipt),
                    admin,
                    new ExamResolutionRequest("zero-op", "ZERO_CONFIRM", 0, "다른 사유")
                )
        ).isInstanceOf(ContestException.class);
    }

    /** 미확인 재처리가 있는 동안 0점 종결로 확정 차단을 해제하지 않는다. */
    @Test
    @DisplayName("PENDING 재처리와 기대 회차 불일치는 실패 제출 종결을 거절한다")
    void pendingRetryAndWrongAttemptBlockResolution() {
        long receipt = failedReceipt(1, null);
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc
            .update(
                "INSERT INTO contest_admin_operations (operation_id,operation_type,context,context_id,receipt_id,"
                    + "request_hash,expected_judge_attempt,state,actor_member_id,reason,created_at,updated_at) "
                    + "VALUES ('retry-pending','EXAM_RETRY','EXAM',?,?,?,1,'PENDING',7,'재처리',?,?)",
                examId,
                receipt,
                "a".repeat(64),
                now,
                now
            );
        assertThatThrownBy(
            () -> service
                .resolve(
                    "EXAM",
                    Long.toString(receipt),
                    admin,
                    new ExamResolutionRequest("zero-op", "ZERO_CONFIRM", 1, "종결")
                )
        ).isInstanceOf(ContestException.class);
        jdbc.update("UPDATE contest_admin_operations SET state='FAILED' WHERE operation_id='retry-pending'");
        assertThatThrownBy(
            () -> service
                .resolve(
                    "EXAM",
                    Long.toString(receipt),
                    admin,
                    new ExamResolutionRequest("zero-op", "ZERO_CONFIRM", 2, "종결")
                )
        ).isInstanceOf(ContestException.class);
        assertThat(
            jdbc.queryForObject("SELECT is_zero_confirmed FROM exam_submissions WHERE id=?", Boolean.class, receipt)
        ).isFalse();
    }

    /** 실패 접수가 마지막 제출이 아니면 그 뒤의 유효 점수를 유지한다. */
    @Test
    @DisplayName("이전 실패를 0점 종결해도 마지막 유효 제출의 점수를 유지한다")
    void olderFailureDoesNotReplaceLatestScore() {
        long receipt = failedReceipt(1, null);
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).plusSeconds(1);
        jdbc
            .update(
                "INSERT INTO exam_submissions (exam_participant_id,exam_id,problem_id,draft_seq,language,"
                    + "source_code,submission_type,received_at,status,submission_id,judge_attempt,verdict,passed_count,"
                    + "total_count,score,is_zero_confirmed,created_at,updated_at) "
                    + "VALUES (?,?,101,2,'java','코드','MANUAL',?,'JUDGED',9002,1,'AC',1,1,50,false,?,?)",
                participantId,
                examId,
                now,
                now,
                now
            );

        service
            .resolve(
                "EXAM",
                Long.toString(receipt),
                admin,
                new ExamResolutionRequest("older-zero", "ZERO_CONFIRM", 1, "종결")
            );

        assertThat(
            jdbc.queryForObject("SELECT total_score FROM exam_participants WHERE id=?", BigDecimal.class, participantId)
        ).isEqualByComparingTo("50.00");
    }

    /** 재확정은 이전 스냅샷을 보존하고 같은 작업의 재요청에 원래 생성 버전을 반환한다. */
    @Test
    @DisplayName("재확정은 기존 스냅샷을 보존하며 새 버전과 이벤트를 한 번 저장한다")
    void refinalizationPreservesSnapshotAndIsIdempotent() {
        autoSubmissions.finish(examId, "7");
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc.update("UPDATE exams SET status='CLOSED',closed_at=? WHERE id=?", now, examId);
        assertThat(finalization.finalizeExam(Long.parseLong(examId))).isTrue();
        long previous = jdbc.queryForObject("SELECT result_revision FROM exams WHERE id=?", Long.class, examId);
        String original
            = jdbc
                .queryForObject(
                    "SELECT payload FROM result_snapshots WHERE context_id=? " + "AND result_revision=?",
                    String.class,
                    examId,
                    previous
                );
        var request = new ExamRefinalizationRequest("refinalize-op", Long.toString(previous), "결과 확인");

        var first = service.refinalize(examId, admin, request);
        var repeated = service.refinalize(examId, admin, request);

        assertThat(first).isEqualTo(repeated);
        assertThat(first.resultRevision()).isEqualTo(Long.toString(previous + 1));
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT payload FROM result_snapshots WHERE context_id=? AND result_revision=?",
                    String.class,
                    examId,
                    previous
                )
        ).isEqualTo(original);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM result_snapshots WHERE context_id=?", Long.class, examId))
            .isEqualTo(2);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM outbox_events WHERE event_type='ExamFinalized' "
                        + "AND JSON_EXTRACT(payload,'$.isRefinalized')=true",
                    Long.class
                )
        ).isEqualTo(1);
        assertThatThrownBy(
            () -> service
                .refinalize(
                    examId,
                    admin,
                    new ExamRefinalizationRequest("refinalize-op", Long.toString(previous + 1), "결과 확인")
                )
        ).isInstanceOf(ContestException.class);
        service.refinalize(examId, admin, new ExamRefinalizationRequest("second-op", first.resultRevision(), "다시 확인"));
        assertThat(service.refinalize(examId, admin, request)).isEqualTo(first);
    }

    /** 최초 확정 이전에는 새 불변 결과를 만들지 않는다. */
    @Test
    @DisplayName("확정되지 않은 시험은 재확정을 거절한다")
    void refinalizationRequiresFinalizedExam() {
        assertThatThrownBy(
            () -> service.refinalize(examId, admin, new ExamRefinalizationRequest("refinalize-op", "0", "사유"))
        ).isInstanceOf(ContestException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM result_snapshots WHERE context_id=?", Long.class, examId))
            .isZero();
    }

    /** 이벤트 저장 실패는 새 작업·결과 버전·스냅샷도 모두 롤백한다. */
    @Test
    @DisplayName("재확정 Outbox 저장이 실패하면 새 결과와 관리자 작업을 롤백한다")
    void outboxFailureRollsBackRefinalization() {
        autoSubmissions.finish(examId, "7");
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc.update("UPDATE exams SET status='CLOSED',closed_at=? WHERE id=?", now, examId);
        assertThat(finalization.finalizeExam(Long.parseLong(examId))).isTrue();
        long previous = jdbc.queryForObject("SELECT result_revision FROM exams WHERE id=?", Long.class, examId);
        OutboxEventWriter target = AopTestUtils.getUltimateTargetObject(outbox);
        try {
            doThrow(new IllegalStateException("outbox failure"))
                .when(target)
                .append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
            assertThatThrownBy(
                () -> service
                    .refinalize(
                        examId,
                        admin,
                        new ExamRefinalizationRequest("rollback-op", Long.toString(previous), "사유")
                    )
            ).isInstanceOf(IllegalStateException.class).hasMessage("outbox failure");
        } finally {
            reset(target);
        }
        assertThat(jdbc.queryForObject("SELECT result_revision FROM exams WHERE id=?", Long.class, examId))
            .isEqualTo(previous);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM result_snapshots WHERE context_id=?", Long.class, examId))
            .isEqualTo(1);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM contest_admin_operations WHERE operation_id='rollback-op'",
                    Long.class
                )
        ).isZero();
    }

    /** 같은 실패 접수에 대한 재처리 선저장과 수동 종결은 부모 잠금으로 직렬화한다. */
    @Test
    @DisplayName("재처리와 0점 종결이 경합하면 한 작업만 접수된다")
    void retryAndResolutionAreSerialized() throws Exception {
        long receipt = failedReceipt(1, null);
        jdbc.update("UPDATE exam_submissions SET submission_id=9001 WHERE id=?", receipt);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var retry = pool.submit(() -> {
                start.await();
                try {
                    retryStore.prepare(Long.parseLong(examId), receipt, 7, new ExamRetryRequest("retry-op", 1, "재처리"));
                    return true;
                } catch (ContestException rejected) {
                    return false;
                }
            });
            var resolution = pool.submit(() -> {
                start.await();
                try {
                    service
                        .resolve(
                            "EXAM",
                            Long.toString(receipt),
                            admin,
                            new ExamResolutionRequest("zero-op", "ZERO_CONFIRM", 1, "종결")
                        );
                    return true;
                } catch (ContestException rejected) {
                    return false;
                }
            });
            start.countDown();
            assertThat(retry.get(10, TimeUnit.SECONDS)).isNotEqualTo(resolution.get(10, TimeUnit.SECONDS));
        }
    }

    /** 먼저 생성된 읽기 스냅샷이 있어도 이후 커밋된 재처리는 종결을 차단한다. */
    @Test
    @DisplayName("오래된 스냅샷으로 시작한 종결도 커밋된 재처리를 확인한다")
    void committedRetryBlocksResolutionAfterSnapshot() throws Exception {
        long receipt = failedReceipt(1, null);
        var snapshotReady = new CountDownLatch(1);
        var retryCommitted = new CountDownLatch(1);
        try (var pool = Executors.newSingleThreadExecutor()) {
            var resolution = pool.submit(() -> transactions.execute(status -> {
                jdbc.queryForObject("SELECT exam_id FROM exam_submissions WHERE id=?", Long.class, receipt);
                snapshotReady.countDown();
                try {
                    if (!retryCommitted.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("retry did not commit");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
                return service
                    .resolve(
                        "EXAM",
                        Long.toString(receipt),
                        admin,
                        new ExamResolutionRequest("snapshot-zero", "ZERO_CONFIRM", 1, "종결")
                    );
            }));
            try {
                assertThat(snapshotReady.await(10, TimeUnit.SECONDS)).isTrue();
                retryStore
                    .prepare(Long.parseLong(examId), receipt, 7, new ExamRetryRequest("snapshot-retry", 1, "재처리"));
            } finally {
                retryCommitted.countDown();
            }
            assertThatThrownBy(() -> resolution.get(10, TimeUnit.SECONDS))
                .hasRootCauseInstanceOf(ContestException.class);
        }
    }

    private long failedReceipt(int attempt, String failureCode) {
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc
            .update(
                "INSERT INTO exam_submissions (exam_participant_id,exam_id,problem_id,draft_seq,language,"
                    + "source_code,submission_type,received_at,status,judge_attempt,submission_id,is_zero_confirmed,"
                    + "dispatch_failure_code,created_at,updated_at) "
                    + "VALUES (?,?,101,1,'java','원문','AUTO',?,'FAILED',?,?,false,?,?,?)",
                participantId,
                examId,
                now,
                attempt,
                attempt == 0 ? null : 9001L,
                failureCode,
                now,
                now
            );
        return jdbc.queryForObject("SELECT MAX(id) FROM exam_submissions WHERE exam_id=?", Long.class, examId);
    }
}
