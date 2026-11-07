package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
class ExamSubmissionServiceIntegrationTest extends ExamIntegrationTestSupport {
    @Test
    @DisplayName("같은 접수 재요청은 종료와 정지 후에도 중복 저장하지 않는다")
    void submissionIsIdempotentAfterFinish() {
        // given
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        var request = new ExamSubmissionRequest("101", "java", "코드", "1");
        var first = submissions.submit(examId, "7", request);
        jdbc.update("UPDATE exam_participants SET status='FINISHED' WHERE id=?", participantId);
        jdbc.update("UPDATE member_replicas SET member_status='SUSPENDED' WHERE member_id=7");
        // when
        var retry = submissions.submit(examId, "7", request);
        // then
        assertThat(retry.examSubmissionId()).isEqualTo(first.examSubmissionId());
        assertThat(retry.receivedAt()).isEqualTo(first.receivedAt());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        verify(limiter, times(1)).acquire(7, 101);
        assertThatThrownBy(
            () -> submissions.submit(examId, "7", new ExamSubmissionRequest("101", "java", "다른 코드", "1"))
        ).isInstanceOf(ContestException.class);
    }

    @Test
    @DisplayName("최신 Draft와 다른 동일 순번 제출은 거절한다")
    void rejectsConflictingDraft() {
        // given
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        store.save(store.prepare(examId, "7", "101", new SaveRequest("java", "기존", "1")));
        // when & then
        assertThatThrownBy(() -> submissions.submit(examId, "7", new ExamSubmissionRequest("101", "java", "변경", "1")))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.DRAFT_SEQ_CONFLICT)
            );
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
    }

    @Test
    @DisplayName("Outbox 저장 실패는 직접 제출과 Draft를 모두 롤백한다")
    void rollsBackSubmissionWhenOutboxFails() {
        // given
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        doThrow(new IllegalStateException("outbox failure"))
            .when(outbox)
            .append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        // when & then
        assertThatThrownBy(() -> submissions.submit(examId, "7", new ExamSubmissionRequest("101", "java", "코드", "1")))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_drafts", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
    }

    @Test
    @DisplayName("동시 동일 제출은 하나의 접수와 이벤트를 반환한다")
    void concurrentDuplicateSubmissions() throws Exception {
        // given
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        var request = new ExamSubmissionRequest("101", "java", "코드", "1");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                return submissions.submit(examId, "7", request);
            });
            var second = pool.submit(() -> {
                start.await();
                return submissions.submit(examId, "7", request);
            });
            // when
            start.countDown();
            var firstResult = first.get(10, TimeUnit.SECONDS);
            var secondResult = second.get(10, TimeUnit.SECONDS);
            // then
            assertThat(secondResult.examSubmissionId()).isEqualTo(firstResult.examSubmissionId());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("직렬화 크기가 큰 기존 접수도 동일 본문이면 다시 반환한다")
    void retriesPreviouslyAcceptedLargeEvent() {
        // given
        var code = String.valueOf((char)1).repeat(50000);
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc
            .update(
                "INSERT INTO exam_submissions (exam_participant_id,exam_id,problem_id,draft_seq,language,"
                    + "source_code,submission_type,received_at,status,is_zero_confirmed,created_at,updated_at) "
                    + "VALUES (?,?,101,1,'JAVA',?,'MANUAL',?,'REQUESTED',false,?,?)",
                participantId,
                examId,
                code,
                now,
                now,
                now
            );
        // when
        var result = submissions.submit(examId, "7", new ExamSubmissionRequest("101", "java", code, "1"));
        // then
        assertThat(result.status()).isEqualTo("REQUESTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions", Integer.class)).isEqualTo(1);
        org.mockito.Mockito.verifyNoInteractions(content, limiter);
    }

}
