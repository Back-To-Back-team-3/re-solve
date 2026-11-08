package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.dto.ExamSubmissionRequest;
import com.backtoback.contest.exam.service.ExamDraftStore.SaveRequest;
import com.backtoback.contest.exam.store.ExamSubmissionRequestStore;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

/** 수동 종료·자동 제출의 최신 Draft 선택과 원자성을 검증한다. */
class ExamAutoSubmissionServiceIntegrationTest extends ExamIntegrationTestSupport {
    @MockitoSpyBean
    private ExamSubmissionRequestStore requestStore;

    @Test
    @DisplayName("최신 공백 Draft는 이전 코드를 자동 제출하지 않고 종료한다")
    void latestBlankDraftSuppressesEarlierCode() {
        store.save(store.prepare(examId, "7", "101", new SaveRequest("java", "이전", "1")));
        store.save(store.prepare(examId, "7", "101", new SaveRequest("java", " \n\t", "2")));

        assertThat(autoSubmissions.finish(examId, "7").autoSubmissionIds()).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions", Integer.class)).isZero();
    }

    @ParameterizedTest
    @ValueSource(
        booleans = {
            false,
            true
        }
    )
    @DisplayName("지원하지 않는 기존 Draft도 수동·자동 종료에서 원문을 보존한 실패 접수로 종료한다")
    void unsupportedDraftFinishesWithFailedReceipt(boolean automatic) {
        // given
        store.save(store.prepare(examId, "7", "101", new SaveRequest("cpp", "기존 코드", "1")));
        store.save(store.prepare(examId, "7", "102", new SaveRequest("java", "정상 코드", "1")));
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        // when
        if (automatic) {
            jdbc
                .update(
                    "UPDATE exam_participants SET personal_ends_at=? WHERE id=?",
                    now.minusSeconds(4),
                    participantId
                );
            assertThat(autoSubmissions.finishDue(Long.parseLong(examId), 7, participantId)).isTrue();
            assertThat(autoSubmissions.finishDue(Long.parseLong(examId), 7, participantId)).isFalse();
        } else {
            var first = autoSubmissions.finish(examId, "7");
            assertThat(first.autoSubmissionIds()).hasSize(2);
            assertThat(autoSubmissions.finish(examId, "7").autoSubmissionIds()).isEqualTo(first.autoSubmissionIds());
        }
        // then
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source_code FROM exam_drafts WHERE problem_id=101", String.class))
            .isEqualTo("기존 코드");
        assertThat(jdbc.queryForObject("SELECT source_code FROM exam_submissions WHERE problem_id=101", String.class))
            .isEqualTo("기존 코드");
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT status='FAILED' AND dispatch_failure_code='UNSUPPORTED_LANGUAGE' AND judge_attempt=0 "
                        + "AND submission_id IS NULL AND request_snapshot IS NULL AND is_zero_confirmed=false "
                        + "FROM exam_submissions WHERE problem_id=101",
                    Boolean.class
                )
        ).isTrue();
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT status='FINISHED' AND auto_submitted_at IS NOT NULL FROM exam_participants WHERE id=?",
                    Boolean.class,
                    participantId
                )
        ).isTrue();
        jdbc.update("UPDATE exams SET status='CLOSED',closed_at=? WHERE id=?", now, examId);
        jdbc.update("UPDATE exam_submissions SET status='JUDGED',score=0 WHERE problem_id=102");
        assertThat(finalization.finalizeExam(Long.parseLong(examId))).isFalse();
    }

    @ParameterizedTest
    @ValueSource(
        booleans = {
            false,
            true
        }
    )
    @DisplayName("언어 조회 후 Draft가 추가되거나 고정 회차가 바뀌면 접수와 종료를 롤백한다")
    void changedDraftOrRevisionRollsBackFinish(boolean missingLanguages) {
        // given
        store.save(store.prepare(examId, "7", "101", new SaveRequest("java", "코드", "1")));
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willAnswer(invocation -> {
            if (missingLanguages) {
                store.save(store.prepare(examId, "7", "102", new SaveRequest("java", "추가 코드", "1")));
            } else {
                jdbc
                    .update(
                        "UPDATE exam_problems SET problem_revision_id=2001 WHERE exam_id=? AND problem_id=101",
                        examId
                    );
            }
            return java.util.List.of("java");
        });
        // when & then
        assertThatThrownBy(() -> autoSubmissions.finish(examId, "7"))
            .isInstanceOf(ContestException.class)
            .extracting("errorCode")
            .isEqualTo(ContestErrorCode.UNSUPPORTED_LANGUAGE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM exam_participants WHERE id=?", String.class, participantId))
            .isEqualTo("STARTED");
    }

    @Test
    @DisplayName("자동 제출의 보존 실패는 접수와 종료를 함께 롤백한다")
    void autoSnapshotFailureRollsBackFinish() {
        store.save(store.prepare(examId, "7", "101", new SaveRequest("java", "코드", "1")));
        ExamSubmissionRequestStore target = AopTestUtils.getUltimateTargetObject(requestStore);
        doThrow(new IllegalStateException("snapshot failure")).when(target).preserve(anyLong(), any());
        assertThatThrownBy(() -> autoSubmissions.finish(examId, "7")).hasMessage("snapshot failure");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM exam_participants WHERE id=?", String.class, participantId))
            .isEqualTo("STARTED");
    }

    @Test
    @DisplayName("자동 제출도 최초 요청의 전체 메시지를 접수와 함께 보존한다")
    void autoSubmissionPreservesOriginalMessage() {
        store.save(store.prepare(examId, "7", "101", new SaveRequest("java", "코드", "1")));
        autoSubmissions.finish(examId, "7");
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT request_snapshot=payload FROM exam_submissions "
                        + "JOIN outbox_events ON aggregate_id=CAST(exam_submissions.id AS CHAR) "
                        + "WHERE event_type='ExamSubmissionRequested'",
                    Boolean.class
                )
        ).isTrue();
    }

    @Test
    @DisplayName("수동 종료는 최신 Draft만 자동 제출하고 반복 요청에도 중복하지 않는다")
    void manualFinishSubmitsLatestDraftOnce() {
        // given
        store.save(store.prepare(examId, "7", "101", new SaveRequest("java", "이전", "1")));
        store.save(store.prepare(examId, "7", "101", new SaveRequest("cpp", "최신", "2")));
        store.save(store.prepare(examId, "7", "102", new SaveRequest("java", "", "1")));
        // when
        var result = autoSubmissions.finish(examId, "7");
        var retry = autoSubmissions.finish(examId, "7");
        // then
        assertThat(result.status()).isEqualTo("FINISHED");
        assertThat(result.autoSubmissionIds()).hasSize(1).isEqualTo(retry.autoSubmissionIds());
        assertThat(jdbc.queryForObject("SELECT source_code FROM exam_submissions", String.class)).isEqualTo("최신");
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT received_at=personal_ends_at FROM exam_submissions s "
                        + "JOIN exam_participants p ON p.id=s.exam_participant_id",
                    Boolean.class
                )
        ).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("크기를 초과한 기존 Draft는 원문을 보존한 실패 접수로 종료하고 결과 확정을 보류한다")
    void oversizedExistingDraftFinishesWithFailedReceipt() {
        // given
        store.save(store.prepare(examId, "7", "101", new SaveRequest("java", "정상 코드", "1")));
        var code = String.valueOf((char)1).repeat(50000);
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc
            .update(
                "INSERT INTO exam_drafts (exam_participant_id,problem_id,language,source_code,seq,saved_at,"
                    + "version,created_at,updated_at) VALUES (?,102,'JAVA',?,1,?,0,?,?)",
                participantId,
                code,
                now,
                now,
                now
            );
        var before
            = jdbc
                .queryForObject(
                    "SELECT personal_ends_at FROM exam_participants WHERE id=?",
                    LocalDateTime.class,
                    participantId
                );
        // when
        var finished = autoSubmissions.finish(examId, "7");
        var retry = autoSubmissions.finish(examId, "7");
        // then
        assertThat(finished.autoSubmissionIds()).hasSize(2).isEqualTo(retry.autoSubmissionIds());
        assertThat(jdbc.queryForObject("SELECT status FROM exam_participants WHERE id=?", String.class, participantId))
            .isEqualTo("FINISHED");
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT personal_ends_at FROM exam_participants WHERE id=?",
                    LocalDateTime.class,
                    participantId
                )
        ).isBeforeOrEqualTo(before);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT source_code FROM exam_drafts WHERE exam_participant_id=? AND problem_id=102",
                    String.class,
                    participantId
                )
        ).isEqualTo(code);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT source_code FROM exam_submissions WHERE problem_id=102", String.class))
            .isEqualTo(code);
        assertThat(jdbc.queryForObject("SELECT status FROM exam_submissions WHERE problem_id=102", String.class))
            .isEqualTo("FAILED");
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT dispatch_failure_code='CODE_TOO_LARGE' AND judge_attempt=0 AND submission_id IS NULL "
                        + "AND request_snapshot IS NULL AND is_zero_confirmed=false "
                        + "FROM exam_submissions WHERE problem_id=102",
                    Boolean.class
                )
        ).isTrue();
        jdbc.update("UPDATE exams SET status='CLOSED',closed_at=? WHERE id=?", now, examId);
        jdbc.update("UPDATE exam_submissions SET status='JUDGED',score=0 WHERE problem_id=101");
        assertThat(finalization.finalizeExam(Long.parseLong(examId))).isFalse();
        // 관리자 종결 처리 후의 저장 상태를 구성해 기존 확정 경로와 연결되는지 확인한다.
        jdbc.update("UPDATE exam_submissions SET is_zero_confirmed=true,score=0 WHERE problem_id=102");
        assertThat(finalization.finalizeExam(Long.parseLong(examId))).isTrue();
    }

    @Test
    @DisplayName("자동 제출은 개인 마감 3초 뒤에만 실행한다")
    void automaticSubmissionWaitsThreeSeconds() {
        // given
        store.save(store.prepare(examId, "7", "101", new SaveRequest("java", "코드", "1")));
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);
        ExamAutoSubmissionService target = AopTestUtils.getUltimateTargetObject(autoSubmissions);
        Clock original = (Clock)ReflectionTestUtils.getField(target, "clock");
        ReflectionTestUtils
            .setField(
                target,
                "clock",
                Clock.fixed(now.atZone(ClockConfiguration.SERVICE_ZONE).toInstant(), ClockConfiguration.SERVICE_ZONE)
            );
        try {
            var deadline = now.minusSeconds(3).plusNanos(1000);
            jdbc.update("UPDATE exam_participants SET personal_ends_at=? WHERE id=?", deadline, participantId);
            assertThat(autoSubmissions.finishDue(Long.parseLong(examId), 7, participantId)).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions", Integer.class)).isZero();
            deadline = now.minusSeconds(3);
            jdbc.update("UPDATE exam_participants SET personal_ends_at=? WHERE id=?", deadline, participantId);
            assertThat(autoSubmissions.finishDue(Long.parseLong(examId), 7, participantId)).isTrue();
            assertThat(autoSubmissions.finishDue(Long.parseLong(examId), 7, participantId)).isFalse();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT received_at FROM exam_submissions", LocalDateTime.class))
                .isEqualTo(deadline);
        } finally {
            ReflectionTestUtils.setField(target, "clock", original);
        }
    }

    @Test
    @DisplayName("직접 제출과 수동 종료가 경합하면 동일 Draft를 중복 접수하지 않는다")
    void concurrentSubmissionAndFinish() throws Exception {
        // given
        store.save(store.prepare(examId, "7", "101", new SaveRequest("java", "코드", "1")));
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var manual = pool.submit(() -> {
                start.await();
                try {
                    return submissions.submit(examId, "7", new ExamSubmissionRequest("101", "java", "코드", "1"));
                } catch (ContestException failure) {
                    assertThat(failure.getErrorCode()).isEqualTo(ContestErrorCode.EXAM_ALREADY_FINISHED);
                    return null;
                }
            });
            var finish = pool.submit(() -> {
                start.await();
                return autoSubmissions.finish(examId, "7");
            });
            // when
            start.countDown();
            manual.get(10, TimeUnit.SECONDS);
            finish.get(10, TimeUnit.SECONDS);
        }
        // then
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
    }

}
