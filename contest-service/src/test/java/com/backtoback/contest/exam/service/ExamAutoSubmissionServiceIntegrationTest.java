package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.dto.ExamSubmissionRequest;
import com.backtoback.contest.exam.service.ExamDraftStore.SaveRequest;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

/** 시험 기능의 저장 및 동시성 통합 검증이다. */
class ExamAutoSubmissionServiceIntegrationTest extends ExamIntegrationTestSupport {
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
    @DisplayName("이벤트 크기를 초과한 기존 Draft는 자동 제출과 종료를 롤백한다")
    void oversizedExistingDraftRollsBackFinish() {
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
        // when & then
        assertThatThrownBy(() -> autoSubmissions.finish(examId, "7"))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode()).isEqualTo(ContestErrorCode.CODE_TOO_LARGE)
            );
        assertThat(jdbc.queryForObject("SELECT status FROM exam_participants WHERE id=?", String.class, participantId))
            .isEqualTo("STARTED");
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT personal_ends_at FROM exam_participants WHERE id=?",
                    LocalDateTime.class,
                    participantId
                )
        ).isEqualTo(before);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT source_code FROM exam_drafts WHERE exam_participant_id=? AND problem_id=102",
                    String.class,
                    participantId
                )
        ).isEqualTo(code);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
    }

    @Test
    @DisplayName("자동 제출은 개인 마감 3초 뒤에만 실행한다")
    void automaticSubmissionWaitsThreeSeconds() {
        // given
        store.save(store.prepare(examId, "7", "101", new SaveRequest("java", "코드", "1")));
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc.update("UPDATE exam_participants SET personal_ends_at=? WHERE id=?", now.minusSeconds(1), participantId);
        // when
        autoSubmissions.finishDue(Long.parseLong(examId), 7, participantId);
        // then
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions", Integer.class)).isZero();
        var deadline = now.minusSeconds(4);
        jdbc.update("UPDATE exam_participants SET personal_ends_at=? WHERE id=?", deadline, participantId);
        autoSubmissions.finishDue(Long.parseLong(examId), 7, participantId);
        autoSubmissions.finishDue(Long.parseLong(examId), 7, participantId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT received_at FROM exam_submissions", LocalDateTime.class))
            .isEqualTo(deadline.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
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
