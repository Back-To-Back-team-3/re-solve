package com.backtoback.contest.admin.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.admin.dto.ExamRetryRequest;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** 시험 잠금 아래 재처리 작업과 접수 상태를 저장한다 (ERD §4.4.18). */
@Service
@RequiredArgsConstructor
public class ExamRetryStore {
    private final ExamRepository exams;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** Judge 호출 전에 작업을 저장해 결과 확정과 직렬화한다. */
    @Transactional
    public Operation prepare(long examId, long receiptId, long actorId, ExamRetryRequest request) {
        var exam = exams.findForUpdate(examId).orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_FOUND));
        String hash = hash(examId, receiptId, actorId, request);
        var existing = find(request.operationId());
        if (existing != null) {
            if (!"EXAM_RETRY".equals(existing.type()) || !"EXAM".equals(existing.context())
                || !existing.hash().equals(hash)) {
                throw new ContestException(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
            return existing;
        }
        var receipt = receipt(examId, receiptId);
        if (exam.getStatus() == ExamStatus.FINALIZED || !"FAILED".equals(receipt.status())
            || receipt.submissionId() == null || receipt.attempt() == null
            || !receipt.attempt().equals(request.expectedJudgeAttempt()) || receipt.attempt() == Integer.MAX_VALUE) {
            throw new ContestException(ContestErrorCode.REJUDGE_NOT_ALLOWED);
        }
        var pending
            = jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM contest_admin_operations WHERE context='EXAM' AND receipt_id=? "
                        + "AND operation_type='EXAM_RETRY' AND state='PENDING'",
                    Long.class,
                    receiptId
                );
        if (pending != null && pending > 0) {
            throw new ContestException(ContestErrorCode.REJUDGE_NOT_ALLOWED);
        }
        var now = now();
        jdbc
            .update(
                "INSERT INTO contest_admin_operations (operation_id,operation_type,context,context_id,receipt_id,"
                    + "request_hash,expected_judge_attempt,state,actor_member_id,reason,created_at,updated_at) "
                    + "VALUES (?,'EXAM_RETRY','EXAM',?,?,?,?,'PENDING',?,?,?,?) "
                    + "ON DUPLICATE KEY UPDATE operation_id=operation_id",
                request.operationId(),
                examId,
                receiptId,
                hash,
                request.expectedJudgeAttempt(),
                actorId,
                request.reason(),
                now,
                now
            );
        var operation = requireLocked(request.operationId());
        if (!"EXAM_RETRY".equals(operation.type()) || !"EXAM".equals(operation.context())
            || !hash.equals(operation.hash())) {
            throw new ContestException(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        return operation;
    }

    /** 늦은 성공 응답은 이미 반영된 목표 결과를 되돌리지 않는다. */
    @Transactional
    public Operation succeeded(String operationId, int target) {
        var snapshot = require(operationId);
        exams.findForUpdate(snapshot.examId()).orElseThrow();
        var current = requireLocked(operationId);
        if (!"PENDING".equals(current.state())) {
            return current;
        }
        if (target != current.expectedAttempt() + 1) {
            throw new IllegalArgumentException("Unexpected target attempt");
        }
        var receipt = receipt(current.examId(), current.receiptId());
        if (receipt.attempt() == null || receipt.attempt() < target) {
            jdbc
                .update(
                    "UPDATE exam_submissions SET status='REQUESTED',is_zero_confirmed=false,updated_at=? "
                        + "WHERE id=? AND exam_id=?",
                    now(),
                    current.receiptId(),
                    current.examId()
                );
        }
        jdbc
            .update(
                "UPDATE contest_admin_operations SET state='SUCCEEDED',target_judge_attempt=?,"
                    + "last_error_code=NULL,updated_at=? WHERE operation_id=? AND state='PENDING'",
                target,
                now(),
                operationId
            );
        audit(current, "SUCCEEDED", target);
        return require(operationId);
    }

    /** 원격에서 미실행이 확정된 경우에만 작업을 실패로 종결한다. */
    @Transactional
    public Operation rejected(String operationId, String errorCode) {
        var snapshot = require(operationId);
        exams.findForUpdate(snapshot.examId()).orElseThrow();
        var current = requireLocked(operationId);
        if (!"PENDING".equals(current.state())) {
            return current;
        }
        var receipt = receipt(current.examId(), current.receiptId());
        if (receipt.attempt() != null && receipt.attempt() > current.expectedAttempt()) {
            return current;
        }
        jdbc
            .update(
                "UPDATE contest_admin_operations SET state='FAILED',last_error_code=?,updated_at=? "
                    + "WHERE operation_id=? AND state='PENDING'",
                errorCode,
                now(),
                operationId
            );
        audit(current, "FAILED", null);
        return require(operationId);
    }

    @Transactional(readOnly = true)
    public Operation require(String operationId) {
        var operation = find(operationId);
        if (operation == null) {
            throw new ContestException(ContestErrorCode.EXAM_NOT_FOUND);
        }
        return operation;
    }

    /** 미확인 작업의 확인 시각과 원격 오류를 남기되 종결하지 않는다. */
    @Transactional
    public Operation noteUnknown(String operationId, String errorCode) {
        jdbc
            .update(
                "UPDATE contest_admin_operations SET last_error_code=?,updated_at=? "
                    + "WHERE operation_id=? AND state='PENDING'",
                errorCode,
                now(),
                operationId
            );
        return require(operationId);
    }

    private Operation requireLocked(String operationId) {
        var rows = jdbc.query(operationSql() + " FOR UPDATE", (rs, row) -> map(rs), operationId);
        if (rows.isEmpty()) {
            throw new ContestException(ContestErrorCode.EXAM_NOT_FOUND);
        }
        return rows.getFirst();
    }

    private Operation find(String operationId) {
        var rows = jdbc.query(operationSql(), (rs, row) -> map(rs), operationId);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String operationSql() {
        return "SELECT operation_id,operation_type,context,context_id,receipt_id,request_hash,"
            + "expected_judge_attempt,target_judge_attempt,state,actor_member_id,reason,last_error_code,updated_at "
            + "FROM contest_admin_operations WHERE operation_id=?";
    }

    private Operation map(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new Operation(
            rs.getString(1),
            rs.getString(2),
            rs.getString(3),
            rs.getLong(4),
            rs.getObject(5, Long.class),
            rs.getString(6),
            rs.getObject(7, Integer.class),
            rs.getObject(8, Integer.class),
            rs.getString(9),
            rs.getLong(10),
            rs.getString(11),
            rs.getString(12),
            rs.getObject(13, LocalDateTime.class)
        );
    }

    private Receipt receipt(long examId, long receiptId) {
        var rows
            = jdbc
                .query(
                    "SELECT submission_id,judge_attempt,status FROM exam_submissions "
                        + "WHERE exam_id=? AND id=? FOR UPDATE",
                    (rs, row) -> new Receipt(
                        rs.getObject(1, Long.class),
                        rs.getObject(2, Integer.class),
                        rs.getString(3)
                    ),
                    examId,
                    receiptId
                );
        if (rows.isEmpty()) {
            throw new ContestException(ContestErrorCode.EXAM_SUBMISSION_NOT_FOUND);
        }
        return rows.getFirst();
    }

    private void audit(Operation operation, String state, Integer target) {
        jdbc
            .update(
                "INSERT INTO audit_logs (actor_id,action,target_type,target_id,before_state,after_state,"
                    + "reason,created_at) " + "VALUES (?,'EXAM_RETRY','EXAM_SUBMISSION',?,?,?,?,?)",
                operation.actorId(),
                Long.toString(operation.receiptId()),
                mapper.writeValueAsString(new AuditState("PENDING", null)),
                mapper.writeValueAsString(new AuditState(state, target)),
                operation.reason(),
                now()
            );
    }

    private String hash(long examId, long receiptId, long actorId, ExamRetryRequest request) {
        try {
            byte[] payload
                = mapper
                    .writeValueAsString(
                        new HashInput(examId, receiptId, actorId, request.expectedJudgeAttempt(), request.reason())
                    )
                    .getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ClockConfiguration.SERVICE_ZONE);
    }

    /** DB에 보존한 작업과 실행자다. */
    public record Operation(
        String id,
        String type,
        String context,
        long examId,
        Long receiptId,
        String hash,
        Integer expectedAttempt,
        Integer targetAttempt,
        String state,
        long actorId,
        String reason,
        String lastError,
        LocalDateTime updatedAt
    ) {
    }
    private record Receipt(Long submissionId, Integer attempt, String status) {
    }
    private record HashInput(long examId, long receiptId, long actorId, int attempt, String reason) {
    }
    private record AuditState(String state, Integer targetJudgeAttempt) {
    }
}
