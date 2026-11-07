package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;

import com.backtoback.common.event.store.JdbcOutboxStore;
import com.backtoback.contest.exam.client.ExamReceiptClient.Receipt;
import com.backtoback.contest.exam.dto.ExamSubmissionRequest;

/** 시험 기능의 저장 및 동시성 통합 검증이다. */
class ExamReceiptReconciliationIntegrationTest extends ExamIntegrationTestSupport {
    @MockitoSpyBean
    private JdbcOutboxStore outboxStore;

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

    @Test
    @DisplayName("원본 Outbox 삭제 후 최초 헤더와 본문을 같은 이벤트로 복원한다")
    void restoresDeletedOriginalRequest() {
        var receipt = submit();
        var before
            = jdbc
                .queryForMap(
                    "SELECT event_id,event_type,schema_version,aggregate_id,correlation_id,"
                        + "occurred_at,payload FROM outbox_events"
                );
        jdbc.update("DELETE FROM outbox_events");
        reconciliation.reconcile(receipt, missing());
        var after
            = jdbc
                .queryForMap(
                    "SELECT event_id,event_type,schema_version,aggregate_id,correlation_id,"
                        + "occurred_at,payload FROM outbox_events"
                );
        assertThat(after).isEqualTo(before);
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events", String.class)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("동시 복구와 반복 복구는 동일한 이벤트 한 건만 남긴다")
    void concurrentRestoreIsIdempotent() throws Exception {
        long receipt = submit();
        String eventId = jdbc.queryForObject("SELECT event_id FROM outbox_events", String.class);
        jdbc.update("DELETE FROM outbox_events");
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                reconciliation.reconcile(receipt, missing());
                return null;
            });
            var second = executor.submit(() -> {
                start.await();
                reconciliation.reconcile(receipt, missing());
                return null;
            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT event_id FROM outbox_events", String.class)).isEqualTo(eventId);
    }

    @Test
    @DisplayName("손상된 요청 보존본과 원본이 없는 기존 접수는 발행하지 않는다")
    void rejectsCorruptedAndMissingSnapshot() {
        long receipt = submit();
        jdbc.update("DELETE FROM outbox_events");
        jdbc
            .update(
                "UPDATE exam_submissions SET request_snapshot=JSON_SET(request_snapshot,'$.sourceCode','위조') "
                    + "WHERE id=?",
                receipt
            );
        assertThatThrownBy(() -> reconciliation.reconcile(receipt, missing()))
            .isInstanceOf(IllegalStateException.class);
        jdbc.update("UPDATE exam_submissions SET request_snapshot=NULL WHERE id=?", receipt);
        assertThatThrownBy(() -> reconciliation.reconcile(receipt, missing()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Original request is unavailable");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT source_code FROM exam_submissions WHERE id=?", String.class, receipt))
            .isEqualTo("코드");
    }

    @Test
    @DisplayName("기존 Outbox와 보존본의 헤더가 다르면 재전달하지 않는다")
    void rejectsConflictingExistingOutbox() {
        long receipt = submit();
        jdbc
            .update(
                "UPDATE outbox_events SET status='PUBLISHED', " + "payload=JSON_SET(payload,'$.correlationId','다른요청')"
            );
        assertThatThrownBy(() -> reconciliation.reconcile(receipt, missing()))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events", String.class)).isEqualTo("PUBLISHED");
    }

    @Test
    @DisplayName("Outbox 복원 실패는 접수와 요청 보존본을 그대로 남긴다")
    void restoreFailureRollsBack() {
        long receipt = submit();
        String original
            = jdbc.queryForObject("SELECT request_snapshot FROM exam_submissions WHERE id=?", String.class, receipt);
        jdbc.update("DELETE FROM outbox_events");
        JdbcOutboxStore target = AopTestUtils.getUltimateTargetObject(outboxStore);
        doThrow(new IllegalStateException("restore failure")).when(target).insert(anyString(), any(), anyString());
        assertThatThrownBy(() -> reconciliation.reconcile(receipt, missing())).hasMessage("restore failure");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
        assertThat(
            jdbc.queryForObject("SELECT request_snapshot FROM exam_submissions WHERE id=?", String.class, receipt)
        ).isEqualTo(original);
    }

    private long submit() {
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        return Long
            .parseLong(
                submissions.submit(examId, "7", new ExamSubmissionRequest("101", "java", "코드", "1")).examSubmissionId()
            );
    }

    private Receipt missing() {
        return new Receipt(false, null, null, null, null, null, null, null, null, null, null, null, null, null);
    }

}
