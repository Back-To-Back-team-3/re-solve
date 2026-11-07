package com.backtoback.contest.exam.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.service.EventMessageDecoder;
import com.backtoback.common.event.store.JdbcOutboxStore;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.client.ExamReceiptClient.Receipt;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.repository.ExamParticipantRepository;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.exam.service.ExamJudgementService.Result;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * 원격 조회 후 원래 접수를 다시 잠가 결과 또는 기존 Outbox를 복구한다.
 * <p>기준 문서</p>
 * <ul><li>스케줄러 v1.0 / §4.6 접수·채점 대사: 연결 검증과 기존 요청 복구</li></ul>
 */
@Service
@RequiredArgsConstructor
public class ExamReceiptReconciliation {
    private final ExamRepository exams;
    private final ExamParticipantRepository participants;
    private final ExamJudgementService results;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final EventMessageDecoder decoder;
    private final JdbcOutboxStore outbox;

    /** 조회 사이 변경된 상태를 잠금 아래 재검증한다. */
    @Transactional
    public void reconcile(long receiptId, Receipt remote) {
        var rows
            = jdbc
                .query(
                    "SELECT s.exam_id,p.member_id FROM exam_submissions s "
                        + "JOIN exam_participants p ON p.id=s.exam_participant_id WHERE s.id=?",
                    (rs, row) -> new Owner(rs.getLong(1), rs.getLong(2)),
                    receiptId
                );
        if (rows.size() != 1) {
            throw new IllegalStateException("Receipt not found");
        }
        var owner = rows.getFirst();
        var exam = exams.findForUpdate(owner.exam()).orElseThrow();
        var participant
            = participants
                .findForUpdate(owner.exam(), owner.member())
                .stream()
                .filter(p -> p.getEnteredAt() != null)
                .findFirst()
                .orElseThrow();
        var current
            = jdbc
                .query(
                    "SELECT s.status,s.problem_id,p.problem_revision_id,s.received_at "
                        + "FROM exam_submissions s JOIN exam_problems p ON p.exam_id=s.exam_id "
                        + "AND p.problem_id=s.problem_id " + "WHERE s.id=? AND s.exam_participant_id=? FOR UPDATE",
                    (rs, row) -> new Current(
                        rs.getString(1),
                        rs.getLong(2),
                        rs.getLong(3),
                        rs.getObject(4, LocalDateTime.class)
                    ),
                    receiptId,
                    participant.getId()
                )
                .stream()
                .findFirst()
                .orElseThrow();
        if (exam.getStatus() == ExamStatus.FINALIZED || "JUDGED".equals(current.status())) {
            return;
        }
        if (remote.found() == null) {
            throw new IllegalArgumentException("Remote receipt presence is unknown");
        }
        if (!remote.found()) {
            restoreRequest(receiptId, owner, participant.getId(), current);
            return;
        }
        if (!"EXAM".equals(remote.context()) || !Long.toString(owner.exam()).equals(remote.contextId())
            || !Long.toString(receiptId).equals(remote.receiptId())
            || !Long.toString(owner.member()).equals(remote.userId())
            || !Long.toString(current.problem()).equals(remote.problemId())
            || !Long.toString(current.revision()).equals(remote.problemRevisionId()) || remote.submittedAt() == null
            || !current
                .receivedAt()
                .equals(remote.submittedAt().atZoneSameInstant(ClockConfiguration.SERVICE_ZONE).toLocalDateTime())) {
            throw new IllegalArgumentException("Remote receipt connection mismatch");
        }
        if (remote.judgeAttempt() == null || remote.judgeAttempt() < 1 || remote.submissionId() == null) {
            throw new IllegalArgumentException("Invalid remote receipt");
        }
        if (Set.of("QUEUED", "JUDGING", "RUNNING", "RETRY_WAITING").contains(remote.status())) {
            return;
        }
        boolean failed = "FAILED".equals(remote.status());
        if (!failed && (!"COMPLETED".equals(remote.status()) || remote.verdict() == null
            || !Set.of("AC", "WA", "TLE", "MLE", "RE", "CE").contains(remote.verdict()) || remote.totalCount() == null
            || remote.totalCount() <= 0 || remote.passedCount() == null || remote.passedCount() < 0
            || remote.passedCount() > remote.totalCount()
            || "AC".equals(remote.verdict()) && !remote.passedCount().equals(remote.totalCount()))) {
            throw new IllegalArgumentException("Invalid remote result");
        }
        results
            .apply(
                new Result(
                    owner.exam(),
                    receiptId,
                    Long.parseLong(remote.submissionId()),
                    owner.member(),
                    current.problem(),
                    current.revision(),
                    remote.judgeAttempt(),
                    failed ? "FAILED" : "JUDGED",
                    failed ? null : remote.verdict(),
                    failed ? null : remote.passedCount(),
                    failed ? null : remote.totalCount(),
                    remote.submittedAt()
                )
            );
    }

    /** 부모·참가·접수 잠금 아래 원본 메시지를 확인한 뒤 같은 이벤트만 재발행한다. */
    private void restoreRequest(long receiptId, Owner owner, long participantId, Current current) {
        var receipt
            = jdbc
                .queryForObject(
                    "SELECT request_snapshot,source_code,language,draft_seq,submission_type "
                        + "FROM exam_submissions WHERE id=?",
                    (rs, row) -> new Original(
                        rs.getString(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getLong(4),
                        rs.getString(5)
                    ),
                    receiptId
                );
        var rows
            = jdbc
                .query(
                    "SELECT id,event_id,event_type,schema_version,aggregate_type,aggregate_id,"
                        + "correlation_id,occurred_at,payload,status FROM outbox_events "
                        + "WHERE event_type='ExamSubmissionRequested' AND aggregate_id=? FOR UPDATE",
                    (rs, row) -> new OutboxRow(
                        rs.getLong(1),
                        rs.getString(2),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getString(5),
                        rs.getString(6),
                        rs.getString(7),
                        rs.getObject(8, LocalDateTime.class),
                        rs.getString(9),
                        rs.getString(10)
                    ),
                    Long.toString(receiptId)
                );
        if (rows.size() > 1) {
            throw new IllegalStateException("Multiple original Outbox events");
        }
        if (rows.isEmpty() && receipt.snapshot() == null) {
            throw new IllegalStateException("Original request is unavailable");
        }
        String message = rows.isEmpty() ? receipt.snapshot() : rows.getFirst().payload();
        var event = decoder.decode(message);
        var metadata = event.metadata();
        var data = event.payload();
        String language = switch (receipt.language()) {
            case "JAVA" -> "java";
            case "PYTHON" -> "python3";
            case "CPP" -> "cpp";
            default -> throw new IllegalStateException("Invalid stored language");
        };
        if (!"ExamSubmissionRequested".equals(metadata.eventType()) || !"v1".equals(metadata.schemaVersion())
            || !Long.toString(receiptId).equals(metadata.aggregateId())
            || message.getBytes(StandardCharsets.UTF_8).length > 262144 || !matches(data, "examId", owner.exam())
            || !matches(data, "examSubmissionId", receiptId) || !matches(data, "participantId", participantId)
            || !matches(data, "userId", owner.member()) || !matches(data, "problemId", current.problem())
            || !matches(data, "problemRevisionId", current.revision()) || !matches(data, "draftSeq", receipt.seq())
            || !matches(data, "language", language) || !matches(data, "sourceCode", receipt.code())
            || !matches(data, "submissionType", receipt.type()) || !receivedAtMatches(data, current.receivedAt())) {
            throw new IllegalStateException("Original request does not match receipt");
        }
        if (!rows.isEmpty()) {
            var row = rows.getFirst();
            if (!"EXAM_SUBMISSION".equals(row.aggregateType()) || !metadata.eventId().toString().equals(row.eventId())
                || !metadata.eventType().equals(row.eventType())
                || !metadata.schemaVersion().equals(row.schemaVersion())
                || !metadata.aggregateId().equals(row.aggregateId())
                || !metadata.correlationId().equals(row.correlation())
                || !metadata.occurredAt().toLocalDateTime().equals(row.occurredAt())
                || receipt.snapshot() != null && !decoder.decode(receipt.snapshot()).equals(event)) {
                throw new IllegalStateException("Original Outbox does not match request");
            }
            if (!Set.of("PENDING", "PUBLISHED", "FAILED").contains(row.status())) {
                throw new IllegalStateException("Invalid original Outbox status");
            }
            if (!"PENDING".equals(row.status())) {
                jdbc
                    .update(
                        "UPDATE outbox_events SET status='PENDING',retry_count=0,published_at=NULL,updated_at=? "
                            + "WHERE id=?",
                        LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE),
                        row.id()
                    );
            }
            return;
        }
        outbox.insert("EXAM_SUBMISSION", metadata, message);
    }

    private boolean receivedAtMatches(JsonNode data, LocalDateTime receivedAt) {
        var value = data.get("receivedAt");
        if (value == null || !value.isString()) {
            return false;
        }
        try {
            return OffsetDateTime
                .parse(value.asString())
                .atZoneSameInstant(ClockConfiguration.SERVICE_ZONE)
                .toLocalDateTime()
                .equals(receivedAt);
        } catch (java.time.DateTimeException failure) {
            return false;
        }
    }

    private boolean matches(JsonNode data, String field, long expected) {
        return matches(data, field, Long.toString(expected));
    }

    private boolean matches(JsonNode data, String field, String expected) {
        var value = data.get(field);
        return value != null && value.isString() && expected.equals(value.asString());
    }

    private record Original(String snapshot, String code, String language, long seq, String type) {
    }
    private record OutboxRow(
        long id,
        String eventId,
        String eventType,
        String schemaVersion,
        String aggregateType,
        String aggregateId,
        String correlation,
        LocalDateTime occurredAt,
        String payload,
        String status
    ) {
    }

    private record Owner(long exam, long member) {
    }
    /** 잠금 아래 확인한 접수 연결이다. */
    private record Current(String status, long problem, long revision, LocalDateTime receivedAt) {
    }
}
