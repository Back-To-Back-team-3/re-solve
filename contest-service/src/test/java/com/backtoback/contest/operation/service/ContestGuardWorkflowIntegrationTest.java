package com.backtoback.contest.operation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.dto.GuardFinishRequest;
import com.backtoback.contest.exam.dto.GuardPrepareRequest;
import com.backtoback.contest.exam.repository.ContestResourceGuardRepository;
import com.backtoback.contest.exam.service.ExamIntegrationTestSupport;
import com.backtoback.contest.exam.service.ProblemSnapshotService;
import com.backtoback.contest.exam.service.ProblemSnapshotService.Snapshot;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

/** 자원 준비와 시험 취소 결과의 영속성을 확인한다. */
class ContestGuardWorkflowIntegrationTest extends ExamIntegrationTestSupport {
    @Autowired
    private ContestGuardWorkflowService workflow;
    @Autowired
    private ContestResourceGuardRepository guards;
    @Autowired
    private MemberGuardTransitions memberTransitions;
    @Autowired
    private ProblemSnapshotService snapshots;

    /** 공유 테스트 DB에 남은 같은 자원의 가드와 준비 이력을 지운다. */
    @BeforeEach
    void clearGuards() {
        jdbc.update("DELETE FROM contest_study_guard_preparations WHERE study_id=12");
        jdbc.update("DELETE FROM contest_resource_guards WHERE resource_type='STUDY' AND resource_id=12");
        jdbc.update("DELETE FROM contest_resource_guards WHERE resource_type='MEMBER' AND resource_id=7");
        jdbc.update("DELETE FROM contest_resource_guards WHERE resource_type='PROBLEM' AND resource_id=101");
    }

    /** 이 테스트가 만든 가드가 다른 테스트의 신규 편입을 막지 않게 한다. */
    @AfterEach
    void removeGuards() {
        clearGuards();
    }

    /** 취소 Outbox와 작업 이력이 같은 최초 시험 목록을 유지하는지 확인한다. */
    @Test
    @DisplayName("스터디 준비 재요청은 최초 취소 목록을 보존하고 현재 대기 시험을 조회한다")
    void studyPreparationKeepsCancellationList() {
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc
            .update(
                "INSERT INTO exams (study_id,creator_id,title,mode,starts_at,ends_at,status,exam_revision,"
                    + "total_score,result_revision,version,created_at,updated_at) "
                    + "VALUES (12,7,'예정','FIXED',?,?,'SCHEDULED',1,100,0,0,?,?)",
                now.plusHours(2),
                now.plusHours(3),
                now,
                now
            );
        String scheduled = jdbc.queryForObject("SELECT MAX(id) FROM exams", String.class);
        var request = new GuardPrepareRequest("study-op", "STUDY_CLOSE", "4");

        var first = workflow.prepare("STUDY", "12", request);
        assertThat(first.canceledExamIds()).containsExactly(scheduled);
        assertThat(first.blockingExamIds()).containsExactly(examId);
        assertThat(first.ready()).isFalse();
        assertThat(jdbc.queryForObject("SELECT status FROM exams WHERE id=?", String.class, scheduled))
            .isEqualTo("CANCELED");
        String payload
            = jdbc
                .queryForObject(
                    "SELECT payload FROM outbox_events WHERE event_type='ExamCanceled' " + "AND aggregate_id=?",
                    String.class,
                    scheduled
                );
        assertThat(payload).contains("스터디 종료 준비로 시험이 취소되었습니다.");
        jdbc.update("UPDATE exams SET status='CLOSED' WHERE id=?", examId);

        var repeated = workflow.prepare("STUDY", "12", request);
        assertThat(repeated.canceledExamIds()).containsExactly(scheduled);
        assertThat(repeated.blockingExamIds()).isEmpty();
        assertThat(repeated.ready()).isTrue();
        workflow.finish("STUDY", "12", new GuardFinishRequest("study-op", "4", "취소"), false);
        workflow.prepare("STUDY", "12", new GuardPrepareRequest("next-op", "STUDY_CLOSE", "4"));
        var historical = workflow.prepare("STUDY", "12", request);
        assertThat(historical.canceledExamIds()).containsExactly(scheduled);
        assertThat(historical.operationResult()).isEqualTo("ABORTED");
        assertThat(historical.ready()).isFalse();
    }

    /** 준비 거절이 새 가드 행까지 롤백하는지 확인한다. */
    @Test
    @DisplayName("실제 입장한 미확정 시험이 있으면 회원 탈퇴 준비를 남기지 않는다")
    void activeMemberCannotPrepare() {
        assertThatThrownBy(
            () -> workflow.prepare("MEMBER", "7", new GuardPrepareRequest("member-op", "WITHDRAWAL", "4"))
        ).isInstanceOf(ContestException.class);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM contest_resource_guards " + "WHERE resource_type='MEMBER' AND resource_id=7",
                    Long.class
                )
        ).isZero();
    }

    /** 진행 사용 거절 뒤 확정 취소가 준비 전 상태로 돌아가는지 확인한다. */
    @Test
    @DisplayName("진행 중 시험의 고정 문제는 준비를 거절하고 종료 뒤에만 준비한다")
    void activeProblemCannotPrepare() {
        var request = new GuardPrepareRequest("problem-op", "PROBLEM_HIDE", "4");
        assertThatThrownBy(() -> workflow.prepare("PROBLEM", "101", request)).isInstanceOf(ContestException.class);
        jdbc.update("UPDATE exams SET status='CLOSED' WHERE id=?", examId);
        var prepared = workflow.prepare("PROBLEM", "101", request);
        assertThat(prepared.state()).isEqualTo("PREPARED");
        assertThat(prepared.ready()).isTrue();
        var canceled = workflow.finish("PROBLEM", "101", new GuardFinishRequest("problem-op", "4", "취소"), false);
        assertThat(canceled.state()).isEqualTo("OPEN");
        assertThat(canceled.operationResult()).isEqualTo("ABORTED");
    }

    /** 직접 취소도 대사 경로와 같은 탈퇴 차단을 유지한다. */
    @Test
    @DisplayName("최신 탈퇴 이벤트를 관찰한 회원 가드는 직접 취소 후에도 차단한다")
    void memberCancelKeepsObservedWithdrawal() {
        // given
        jdbc.update("UPDATE exams SET status='FINALIZED' WHERE id=?", examId);
        workflow.prepare("MEMBER", "7", new GuardPrepareRequest("member-op", "WITHDRAWAL", "4"));
        transactions.executeWithoutResult(status -> memberTransitions.observeWithdrawal(7, 5));

        // when
        var canceled = workflow.finish("MEMBER", "7", new GuardFinishRequest("member-op", "4", "취소"), false);

        // then
        assertThat(canceled.state()).isEqualTo("BLOCKED");
        assertThat(canceled.operationResult()).isEqualTo("ABORTED");
        assertThat(canceled.sourceVersion()).isEqualTo("5");
    }

    /** 취소의 기대 버전과 실제 문제 스냅샷의 버전을 구분한다. */
    @Test
    @DisplayName("숨김 취소 후 기대 버전과 같은 문제 스냅샷을 반영한다")
    void problemCancelKeepsSnapshotVersion() {
        // given
        jdbc.update("UPDATE exams SET status='CLOSED' WHERE id=?", examId);
        var original = new Snapshot(101, 4, "GENERAL", "PUBLIC", 1001L, 1, List.of("java"), null);
        transactions.executeWithoutResult(status -> snapshots.apply(original));
        workflow.prepare("PROBLEM", "101", new GuardPrepareRequest("problem-op", "PROBLEM_HIDE", "5"));

        // when
        var canceled = workflow.finish("PROBLEM", "101", new GuardFinishRequest("problem-op", "5", "취소"), false);
        var updated = new Snapshot(101, 5, "GENERAL", "PUBLIC", 1002L, 2, List.of("java"), null);
        transactions.executeWithoutResult(status -> snapshots.apply(updated));

        // then
        assertThat(canceled.sourceVersion()).isEqualTo("4");
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT source_revision_id FROM contest_resource_guards "
                        + "WHERE resource_type='PROBLEM' AND resource_id=101",
                    Long.class
                )
        ).isEqualTo(1002L);
    }

    /** 더 최신 공개 이벤트와 늦은 완료 요청의 버전 순서를 대조한다. */
    @Test
    @DisplayName("문제 완료는 더 최신 공개 버전을 보존하고 원래 결과 회차만 허용한다")
    void problemCompletionUsesOriginalResultVersion() {
        jdbc.update("UPDATE exams SET status='CLOSED' WHERE id=?", examId);
        workflow.prepare("PROBLEM", "101", new GuardPrepareRequest("problem-op", "PROBLEM_HIDE", "4"));
        jdbc
            .update(
                "UPDATE contest_resource_guards SET source_version=8,source_scope='GENERAL',"
                    + "source_status='PUBLIC' WHERE resource_type='PROBLEM' AND resource_id=101"
            );

        var completed = workflow.finish("PROBLEM", "101", new GuardFinishRequest("problem-op", "5", "완료"), true);

        assertThat(completed.state()).isEqualTo("OPEN");
        assertThat(completed.sourceVersion()).isEqualTo("8");
        assertThatThrownBy(
            () -> workflow.finish("PROBLEM", "101", new GuardFinishRequest("problem-op", "6", "완료"), true)
        ).isInstanceOf(ContestException.class);
    }

    /** 오래된 작업 응답이 현재 준비를 종결하지 않는지 확인한다. */
    @Test
    @DisplayName("원격 조회 뒤 작업이 바뀌면 이전 문제 결과로 현재 가드를 종결하지 않는다")
    void staleProblemRecoveryCannotCompleteNewWork() {
        jdbc.update("UPDATE exams SET status='CLOSED' WHERE id=?", examId);
        workflow.prepare("PROBLEM", "101", new GuardPrepareRequest("new-op", "PROBLEM_HIDE", "4"));
        var oldResult
            = new com.backtoback.contest.operation.client.ResourceOperationClient.Result(
                "old-op",
                "COMMITTED",
                "5",
                "PRIVATE"
            );
        assertThat(workflow.reconcile("PROBLEM", 101, "old-op", 4, oldResult)).isFalse();
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT state FROM contest_resource_guards " + "WHERE resource_type='PROBLEM' AND resource_id=101",
                    String.class
                )
        ).isEqualTo("PREPARED");
    }

    /** 신규 편입의 가드 잠금이 끝난 뒤 숨김 준비가 커밋된 사용을 보는지 확인한다. */
    @Test
    @DisplayName("문제 신규 편입이 먼저 커밋되면 경합한 숨김 준비는 사용 중 오류로 거절한다")
    void enrollmentSerializesWithProblemPrepare() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var enrollment = pool.submit(() -> transactions.executeWithoutResult(status -> {
                guards.lockAll(List.of(new ContestResourceGuardRepository.Resource("PROBLEM", 103)));
                jdbc
                    .update(
                        "INSERT INTO exam_problems (exam_id,problem_id,problem_revision_id,problem_title,"
                            + "revision_number,score,display_order,created_at,updated_at) "
                            + "VALUES (?,103,1003,'문제',1,1,3,?,?)",
                        examId,
                        now,
                        now
                    );
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timeout");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            var prepare = pool.submit(() -> {
                try {
                    workflow.prepare("PROBLEM", "103", new GuardPrepareRequest("race", "PROBLEM_HIDE", "4"));
                    return false;
                } catch (ContestException expected) {
                    return expected.getErrorCode() == ContestErrorCode.PROBLEM_IN_ACTIVE_EXAM;
                }
            });
            try {
                assertThat(waitForGuardInsert()).isTrue();
                assertThat(prepare.isDone()).isFalse();
            } finally {
                release.countDown();
            }
            enrollment.get(10, TimeUnit.SECONDS);
            assertThat(prepare.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            jdbc.update("DELETE FROM contest_resource_guards WHERE resource_type='PROBLEM' AND resource_id=103");
        }
    }

    /** 편입 트랜잭션이 잠금을 가진 동안 준비 세션의 가드 INSERT가 실행 중인지 확인한다. */
    private boolean waitForGuardInsert() throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        do {
            var processes = jdbc.queryForList("SHOW FULL PROCESSLIST");
            if (processes
                .stream()
                .anyMatch(
                    row -> row.get("Info") instanceof String sql
                        && sql.startsWith("INSERT INTO contest_resource_guards")
                )) {
                return true;
            }
            Thread.sleep(20);
        } while (System.nanoTime() < deadline);
        return false;
    }

    /** 작업 ID가 일치하는 종료 이벤트만 준비를 완료하는지 확인한다. */
    @Test
    @DisplayName("스터디 종료 이벤트는 같은 준비 작업만 종결하고 다른 작업은 유지한다")
    void studyEventCompletesMatchingOperationOnly() {
        jdbc.update("UPDATE exams SET status='CLOSED' WHERE id=?", examId);
        workflow.prepare("STUDY", "12", new GuardPrepareRequest("study-op", "STUDY_CLOSE", "4"));
        workflow.studyClosed(12, "other-op", 5);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT state FROM contest_resource_guards " + "WHERE resource_type='STUDY' AND resource_id=12",
                    String.class
                )
        ).isEqualTo("PREPARED");
        workflow.studyClosed(12, "study-op", 5);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT operation_result FROM contest_resource_guards "
                        + "WHERE resource_type='STUDY' AND resource_id=12",
                    String.class
                )
        ).isEqualTo("COMMITTED");
    }
}
