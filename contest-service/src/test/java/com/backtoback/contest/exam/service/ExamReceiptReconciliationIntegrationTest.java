package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.backtoback.contest.exam.client.ExamReceiptClient.Receipt;
import com.backtoback.contest.exam.dto.ExamSubmissionRequest;

/** 시험 기능의 저장 및 동시성 통합 검증이다. */
class ExamReceiptReconciliationIntegrationTest extends ExamIntegrationTestSupport {
    @Test
    @DisplayName("Judge에 없는 접수는 기존 Outbox를 같은 이벤트 ID로 재전달한다")
    void requeuesOriginalOutbox() {
        // given
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        var receipt = submissions.submit(examId, "7", new ExamSubmissionRequest("101", "java", "코드", "1"));
        var eventId = jdbc.queryForObject("SELECT event_id FROM outbox_events", String.class);
        jdbc.update("UPDATE outbox_events SET status='PUBLISHED'");
        var missing = new Receipt(false, null, null, null, null, null, null, null, null, null, null, null, null, null);
        // when
        reconciliation.reconcile(Long.parseLong(receipt.examSubmissionId()), missing);
        // then
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT event_id FROM outbox_events", String.class)).isEqualTo(eventId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("대사 응답의 회원 연결이 다르면 기존 접수를 보존한다")
    void rejectsMismatchedRemoteReceipt() {
        // given
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        var receipt = submissions.submit(examId, "7", new ExamSubmissionRequest("101", "java", "코드", "1"));
        var remote
            = new Receipt(
                true,
                "9001",
                "EXAM",
                examId,
                receipt.examSubmissionId(),
                "8",
                "101",
                "1001",
                receipt.receivedAt(),
                "COMPLETED",
                1,
                "AC",
                3,
                3
            );
        // when & then
        assertThatThrownBy(() -> reconciliation.reconcile(Long.parseLong(receipt.examSubmissionId()), remote))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT status FROM exam_submissions WHERE id=?",
                    String.class,
                    receipt.examSubmissionId()
                )
        ).isEqualTo("ACCEPTED");
    }

}
