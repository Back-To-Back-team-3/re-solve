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
import com.backtoback.contest.global.error.ContestException;

/** 자원 준비와 시험 취소 결과의 영속성을 확인한다. */
class ContestGuardWorkflowIntegrationTest extends ExamIntegrationTestSupport {
    @Autowired
    private ContestGuardWorkflowService workflow;
    @Autowired
    private ContestResourceGuardRepository guards;

    @BeforeEach
    void clearGuards() {
        jdbc.update("DELETE FROM contest_study_guard_preparations WHERE study_id=12");
        jdbc.update("DELETE FROM contest_resource_guards WHERE resource_type='STUDY' AND resource_id=12");
        jdbc.update("DELETE FROM contest_resource_guards WHERE resource_type='MEMBER' AND resource_id=7");
        jdbc.update("DELETE FROM contest_resource_guards WHERE resource_type='PROBLEM' AND resource_id=101");
    }

    @AfterEach
    void removeGuards() {
        clearGuards();
    }

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
                    return true;
                }
            });
            release.countDown();
            enrollment.get(10, TimeUnit.SECONDS);
            assertThat(prepare.get(10, TimeUnit.SECONDS)).isTrue();
        } finally {
            release.countDown();
            jdbc.update("DELETE FROM contest_resource_guards WHERE resource_type='PROBLEM' AND resource_id=103");
        }
    }

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
