package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.dto.ExamSubmissionRequest;

/** 시험 기능의 저장 및 동시성 통합 검증이다. */
class ExamJudgementServiceIntegrationTest extends ExamIntegrationTestSupport {
    @Test
    @DisplayName("채점 결과는 절사해 반영하고 같은 회차의 다른 판정은 거절한다")
    void consumesJudgementAndRejectsConflict() {
        // given
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        var receipt = submissions.submit(examId, "7", new ExamSubmissionRequest("101", "java", "코드", "1"));
        var data = new java.util.HashMap<String, Object>();
        data.put("context", "EXAM");
        data.put("examId", examId);
        data.put("examSubmissionId", receipt.examSubmissionId());
        data.put("submissionId", "9001");
        data.put("userId", "7");
        data.put("problemId", "101");
        data.put("problemRevisionId", "1001");
        data.put("judgeAttempt", 1);
        data.put("status", "COMPLETED");
        data.put("verdict", "WA");
        data.put("passedCount", 1);
        data.put("totalCount", 3);
        data.put("submittedAt", receipt.receivedAt().toString());
        var metadata
            = new EventMetadata(
                java.util.UUID.randomUUID(),
                "ExamSubmissionJudged",
                "v1",
                java.time.OffsetDateTime.now(),
                "9001",
                "test"
            );
        var message = codec.serialize(metadata, data);
        var acknowledgements = new java.util.concurrent.atomic.AtomicInteger();
        // when
        assertThatThrownBy(() -> consumer.consume(message, () -> {
            acknowledgements.incrementAndGet();
            throw new IllegalStateException("ack failure");
        })).isInstanceOf(IllegalStateException.class);
        consumer.consume(message, acknowledgements::incrementAndGet);
        // then
        assertThat(acknowledgements).hasValue(2);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT score FROM exam_submissions WHERE id=?",
                    java.math.BigDecimal.class,
                    receipt.examSubmissionId()
                )
        ).isEqualByComparingTo("16.66");
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT total_score FROM exam_participants WHERE id=?",
                    java.math.BigDecimal.class,
                    participantId
                )
        ).isEqualByComparingTo("16.66");
        data.put("verdict", "AC");
        var conflicting
            = new EventMetadata(
                java.util.UUID.randomUUID(),
                "ExamSubmissionJudged",
                "v1",
                java.time.OffsetDateTime.now(),
                "9001",
                "test"
            );
        assertThatThrownBy(
            () -> consumer.consume(codec.serialize(conflicting, data), acknowledgements::incrementAndGet)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThat(acknowledgements).hasValue(2);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM processed_events WHERE event_id=?",
                    Integer.class,
                    conflicting.eventId().toString()
                )
        ).isZero();
    }

    @Test
    @DisplayName("새 제출이 미종결이면 이전 AC 점수를 마지막 제출 점수로 사용하지 않는다")
    void pendingLatestDoesNotReusePreviousScore() {
        // given
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        var first = submissions.submit(examId, "7", new ExamSubmissionRequest("101", "java", "첫 코드", "1"));
        transactions
            .executeWithoutResult(
                status -> judgements
                    .apply(
                        new ExamJudgementService.Result(
                            Long.parseLong(examId),
                            Long.parseLong(first.examSubmissionId()),
                            9001,
                            7,
                            101,
                            1001,
                            1,
                            "JUDGED",
                            "AC",
                            3,
                            3,
                            first.receivedAt()
                        )
                    )
            );
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT total_score FROM exam_participants WHERE id=?",
                    java.math.BigDecimal.class,
                    participantId
                )
        ).isEqualByComparingTo("50.00");
        // when
        submissions.submit(examId, "7", new ExamSubmissionRequest("101", "java", "두 번째 코드", "2"));
        // then
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT total_score FROM exam_participants WHERE id=?",
                    java.math.BigDecimal.class,
                    participantId
                )
        ).isEqualByComparingTo("0.00");
    }

    @Test
    @DisplayName("재처리 목표 회차 뒤에 온 이전 결과는 점수를 되돌리지 않는다")
    void rejectsOldJudgementAfterRetry() {
        // given
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        var receipt = submissions.submit(examId, "7", new ExamSubmissionRequest("101", "java", "코드", "1"));
        long id = Long.parseLong(receipt.examSubmissionId());
        var first
            = new ExamJudgementService.Result(
                Long.parseLong(examId),
                id,
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
            );
        transactions.executeWithoutResult(status -> judgements.apply(first));
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc
            .update(
                "INSERT INTO contest_admin_operations (operation_id,operation_type,context,context_id,receipt_id,"
                    + "request_hash,expected_judge_attempt,target_judge_attempt,state,actor_member_id,reason,"
                    + "created_at,updated_at) "
                    + "VALUES ('retry-test','EXAM_RETRY','EXAM',?,?,?,1,2,'SUCCEEDED',7,'재처리',?,?)",
                examId,
                id,
                "a".repeat(64),
                now,
                now
            );
        var second
            = new ExamJudgementService.Result(
                Long.parseLong(examId),
                id,
                9001,
                7,
                101,
                1001,
                2,
                "JUDGED",
                "CE",
                0,
                3,
                receipt.receivedAt()
            );
        // when
        transactions.executeWithoutResult(status -> judgements.apply(second));
        transactions.executeWithoutResult(status -> judgements.apply(first));
        // then
        assertThat(jdbc.queryForObject("SELECT judge_attempt FROM exam_submissions WHERE id=?", Integer.class, id))
            .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT score FROM exam_submissions WHERE id=?", java.math.BigDecimal.class, id))
            .isEqualByComparingTo("0.00");
    }

}
