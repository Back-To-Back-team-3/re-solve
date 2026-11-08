package com.backtoback.contest.admin.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.common.web.security.user.UserRole;
import com.backtoback.contest.admin.dto.ExamRefinalizationRequest;
import com.backtoback.contest.admin.dto.ExamRefinalizationResponse;
import com.backtoback.contest.admin.dto.ExamResolutionRequest;
import com.backtoback.contest.admin.dto.ExamResolutionResponse;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.exam.service.ExamFinalizationService;
import com.backtoback.contest.exam.service.ExamJudgementService;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** 실패 시험 접수 종결과 확정 결과의 재집계를 관리자 작업으로 기록한다. */
@Service
@RequiredArgsConstructor
public class ExamResolutionService {
    private final ExamRepository exams;
    private final ExamJudgementService judgements;
    private final ExamFinalizationService finalization;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    /**
     * 부모·참가자·접수 잠금 아래 실패 접수를 0점으로 종결하고 점수와 감사를 저장한다.
     * 전달 전 크기 초과·지원 불가 언어 접수는 Judge 연결 없이 회차 0으로 검증한다.
     *
     * @param context 처리 문맥. 시험만 허용한다.
     * @param receiptId 시험 접수 ID
     * @param user Gateway가 인증한 관리자
     * @param request 종결 작업 ID·기대 회차·사유
     * @return 종결된 접수와 결과 버전
     * @throws ContestException 권한·상태·회차 또는 멱등 요청이 일치하지 않는 경우
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public ExamResolutionResponse resolve(
        String context,
        String receiptId,
        GatewayUser user,
        ExamResolutionRequest request
    ) {
        long actor = admin(user);
        long receipt = positiveId(receiptId);
        if (!"EXAM".equals(context) || !"ZERO_CONFIRM".equals(request.action())) {
            throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        var owners
            = jdbc
                .query(
                    "SELECT exam_id,exam_participant_id,problem_id FROM exam_submissions WHERE id=?",
                    (rs, row) -> new Owner(rs.getLong(1), rs.getLong(2), rs.getLong(3)),
                    receipt
                );
        if (owners.isEmpty()) {
            throw new ContestException(ContestErrorCode.EXAM_SUBMISSION_NOT_FOUND);
        }
        var owner = owners.getFirst();
        var exam
            = exams
                .findForUpdate(owner.exam())
                .orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_FOUND));
        String hash
            = hash(
                new HashInput(
                    "EXAM_ZERO_CONFIRM",
                    owner.exam(),
                    receipt,
                    actor,
                    request.expectedJudgeAttempt().longValue(),
                    request.reason()
                )
            );
        // 시험 잠금을 기다리는 사이 커밋된 같은 작업을 문장 단위 읽기로 확인한다.
        var existing = operation(request.operationId());
        if (existing != null) {
            verify(existing, "EXAM_ZERO_CONFIRM", hash);
            Long savedRevision
                = jdbc
                    .queryForObject(
                        "SELECT CAST(JSON_UNQUOTE(JSON_EXTRACT(after_state,"
                            + "'$.resultRevision')) AS UNSIGNED) FROM audit_logs WHERE action='EXAM_ZERO_CONFIRM' "
                            + "AND target_type='EXAM_SUBMISSION' AND target_id=? "
                            + "AND JSON_UNQUOTE(JSON_EXTRACT(after_state,'$.operationId'))=?",
                        Long.class,
                        Long.toString(receipt),
                        request.operationId()
                    );
            return resolutionResponse(request.operationId(), receipt, savedRevision);
        }
        jdbc
            .queryForObject(
                "SELECT id FROM exam_participants WHERE id=? AND exam_id=? FOR UPDATE",
                Long.class,
                owner.participant(),
                owner.exam()
            );
        var stored
            = jdbc
                .queryForObject(
                    "SELECT status,COALESCE(judge_attempt,0),is_zero_confirmed,"
                        + "submission_id,dispatch_failure_code "
                        + "FROM exam_submissions WHERE id=? AND exam_id=? FOR UPDATE",
                    (rs, row) -> new Receipt(
                        rs.getString(1),
                        rs.getInt(2),
                        rs.getBoolean(3),
                        rs.getObject(4, Long.class),
                        rs.getString(5)
                    ),
                    receipt,
                    owner.exam()
                );
        if (exam.getStatus() == ExamStatus.FINALIZED || !"FAILED".equals(stored.status()) || stored.resolved()
            || stored.attempt() != request.expectedJudgeAttempt()
            || (stored.attempt() == 0
                && (stored.submissionId() != null || !("CODE_TOO_LARGE".equals(stored.dispatchFailure())
                    || "UNSUPPORTED_LANGUAGE".equals(stored.dispatchFailure()))))
            || (stored.attempt() > 0 && stored.submissionId() == null)) {
            throw new ContestException(ContestErrorCode.REJUDGE_NOT_ALLOWED);
        }
        var pending
            = jdbc
                .queryForList(
                    "SELECT id FROM contest_admin_operations WHERE context='EXAM' AND context_id=? "
                        + "AND receipt_id=? AND operation_type='EXAM_RETRY' AND state='PENDING' FOR UPDATE",
                    Long.class,
                    owner.exam(),
                    receipt
                );
        if (!pending.isEmpty()) {
            throw new ContestException(ContestErrorCode.REJUDGE_NOT_ALLOWED);
        }
        claim(
            request.operationId(),
            "EXAM_ZERO_CONFIRM",
            owner.exam(),
            receipt,
            hash,
            request.expectedJudgeAttempt(),
            null,
            actor,
            request.reason()
        );
        var now = now();
        jdbc.update("UPDATE exam_submissions SET is_zero_confirmed=true,score=0,updated_at=? WHERE id=?", now, receipt);
        judgements.refreshProblem(owner.exam(), owner.participant(), owner.problem(), now);
        long revision = jdbc.queryForObject("SELECT result_revision FROM exams WHERE id=?", Long.class, owner.exam());
        audit(
            actor,
            "EXAM_ZERO_CONFIRM",
            "EXAM_SUBMISSION",
            receipt,
            request.reason(),
            new AuditState("FAILED", false, null, null),
            new AuditState("FAILED", true, request.operationId(), Long.toString(revision)),
            now
        );
        return resolutionResponse(request.operationId(), receipt, revision);
    }

    /**
     * 이전 확정 버전과 미종결 작업을 확인하고 새 스냅샷·이벤트·감사를 같은 트랜잭션에 저장한다.
     * 같은 작업 재요청은 그 작업이 만든 스냅샷을 반환한다.
     *
     * @param examId 시험 ID
     * @param user Gateway가 인증한 관리자
     * @param request 작업 ID·이전 결과 버전·사유
     * @return 해당 작업에서 생성한 결과 버전
     * @throws ContestException 권한·상태·버전 또는 멱등 요청이 일치하지 않는 경우
     */
    @Transactional
    public ExamRefinalizationResponse refinalize(String examId, GatewayUser user, ExamRefinalizationRequest request) {
        long actor = admin(user);
        long id = positiveId(examId);
        long expected = nonnegative(request.expectedResultRevision());
        var exam = exams.findForUpdate(id).orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_FOUND));
        String hash = hash(new HashInput("RESULT_REFINALIZE", id, null, actor, expected, request.reason()));
        var existing = operation(request.operationId());
        if (existing != null) {
            verify(existing, "RESULT_REFINALIZE", hash);
            return snapshot(id, Math.incrementExact(expected));
        }
        if (exam.getStatus() != ExamStatus.FINALIZED) {
            throw new ContestException(ContestErrorCode.RESULT_NOT_FINALIZED);
        }
        if (exam.getResultRevision() != expected || blocked(id)) {
            throw new ContestException(ContestErrorCode.RESULT_FINALIZATION_BLOCKED);
        }
        claim(request.operationId(), "RESULT_REFINALIZE", id, null, hash, null, expected, actor, request.reason());
        var result = finalization.refinalize(id, expected);
        audit(actor, "RESULT_REFINALIZE", "EXAM", id, request.reason(), expected, expected + 1, now());
        return new ExamRefinalizationResponse(examId, "FINALIZED", result.resultRevision(), result.finalizedAt());
    }

    /** 미종결 제출과 원격 결과가 불명인 관리자 작업은 재확정을 막는다. */
    private boolean blocked(long exam) {
        Long count
            = jdbc
                .queryForObject(
                    "SELECT (SELECT COUNT(*) FROM exam_submissions WHERE exam_id=? "
                        + "AND (status NOT IN ('JUDGED','FAILED') OR (status='FAILED' AND is_zero_confirmed=false))) "
                        + "+ (SELECT COUNT(*) FROM contest_admin_operations WHERE context='EXAM' AND context_id=? "
                        + "AND state='PENDING')",
                    Long.class,
                    exam,
                    exam
                );
        return count == null || count > 0;
    }

    /** 유일 작업 ID 충돌은 다른 본문과 섞이지 않게 트랜잭션 전체를 거절한다. */
    private void claim(
        String id,
        String type,
        long exam,
        Long receipt,
        String hash,
        Integer attempt,
        Long revision,
        long actor,
        String reason
    ) {
        var now = now();
        try {
            jdbc
                .update(
                    "INSERT INTO contest_admin_operations (operation_id,operation_type,context,context_id,"
                        + "receipt_id,request_hash,expected_judge_attempt,expected_result_revision,"
                        + "state,actor_member_id,"
                        + "reason,created_at,updated_at) VALUES (?,?,'EXAM',?,?,?,?,?,'SUCCEEDED',?,?,?,?)",
                    id,
                    type,
                    exam,
                    receipt,
                    hash,
                    attempt,
                    revision,
                    actor,
                    reason,
                    now,
                    now
                );
        } catch (DuplicateKeyException conflict) {
            throw new ContestException(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT, conflict);
        }
    }

    private StoredOperation operation(String id) {
        var rows
            = jdbc
                .query(
                    "SELECT operation_type,context,request_hash FROM contest_admin_operations WHERE operation_id=?",
                    (rs, row) -> new StoredOperation(rs.getString(1), rs.getString(2), rs.getString(3)),
                    id
                );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private void verify(StoredOperation operation, String type, String hash) {
        if (!type.equals(operation.type()) || !"EXAM".equals(operation.context()) || !hash.equals(operation.hash())) {
            throw new ContestException(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
    }

    private ExamResolutionResponse resolutionResponse(String operation, long receipt, long revision) {
        return new ExamResolutionResponse(
            operation,
            "EXAM",
            Long.toString(receipt),
            "FAILED",
            true,
            Long.toString(revision)
        );
    }

    /** 재요청은 현재 시험 버전이 아니라 원래 작업이 만든 불변 스냅샷을 반환한다. */
    private ExamRefinalizationResponse snapshot(long exam, long revision) {
        var json
            = jdbc
                .queryForObject(
                    "SELECT payload FROM result_snapshots WHERE context='EXAM' "
                        + "AND context_id=? AND result_revision=?",
                    String.class,
                    exam,
                    revision
                );
        var result = mapper.readValue(json, ExamFinalizationService.Snapshot.class);
        return new ExamRefinalizationResponse(
            Long.toString(exam),
            "FINALIZED",
            result.resultRevision(),
            result.finalizedAt()
        );
    }

    private void audit(
        long actor,
        String action,
        String target,
        long id,
        String reason,
        Object before,
        Object after,
        LocalDateTime now
    ) {
        jdbc
            .update(
                "INSERT INTO audit_logs (actor_id,action,target_type,target_id,before_state,after_state,reason,"
                    + "created_at) VALUES (?,?,?,?,?,?,?,?)",
                actor,
                action,
                target,
                Long.toString(id),
                mapper.writeValueAsString(before),
                mapper.writeValueAsString(after),
                reason,
                now
            );
    }

    /** 작업 종류·대상·집행자·기대 버전·사유를 함께 해시해 다른 본문을 구분한다. */
    private String hash(HashInput input) {
        try {
            return HexFormat
                .of()
                .formatHex(
                    MessageDigest
                        .getInstance("SHA-256")
                        .digest(mapper.writeValueAsString(input).getBytes(StandardCharsets.UTF_8))
                );
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private long admin(GatewayUser user) {
        if (user.role() != UserRole.ADMIN) {
            throw new ContestException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        return positiveId(user.userId());
    }

    private long positiveId(String value) {
        long result = nonnegative(value);
        if (result == 0) {
            throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        return result;
    }

    private long nonnegative(String value) {
        try {
            if (value != null && value.matches("0|[1-9][0-9]*")) {
                return Long.parseLong(value);
            }
        } catch (NumberFormatException ignored) {
            // 저장 가능한 BIGINT 범위만 허용한다.
        }
        throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
    }

    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant().truncatedTo(ChronoUnit.MICROS), ClockConfiguration.SERVICE_ZONE);
    }

    private record Owner(long exam, long participant, long problem) {
    }
    private record Receipt(String status, int attempt, boolean resolved, Long submissionId, String dispatchFailure) {
    }
    private record StoredOperation(String type, String context, String hash) {
    }
    private record HashInput(String type, long exam, Long receipt, long actor, long expected, String reason) {
    }
    private record AuditState(String status, boolean resolved, String operationId, String resultRevision) {
    }
}
