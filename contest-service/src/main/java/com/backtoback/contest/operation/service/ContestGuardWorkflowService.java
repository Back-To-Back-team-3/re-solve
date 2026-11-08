package com.backtoback.contest.operation.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.domain.ExamStatus;
import com.backtoback.contest.exam.dto.GuardCompletionResponse;
import com.backtoback.contest.exam.dto.GuardFinishRequest;
import com.backtoback.contest.exam.dto.GuardPreparationResponse;
import com.backtoback.contest.exam.dto.GuardPrepareRequest;
import com.backtoback.contest.exam.repository.ContestResourceGuardRepository;
import com.backtoback.contest.exam.repository.ContestResourceGuardRepository.Resource;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.exam.service.ExamManagementService.CanceledPayload;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;
import com.backtoback.contest.operation.client.ResourceOperationClient.Result;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** 자원 가드의 준비와 소유 서비스 작업 종결을 직렬화한다 (API 명세서 §4.7.3·§4.7.4). */
@Service
@RequiredArgsConstructor
public class ContestGuardWorkflowService {
    private static final String STUDY_CANCELLATION_REASON = "스터디 종료 준비로 시험이 취소되었습니다.";
    private final ContestResourceGuardRepository guards;
    private final ExamRepository exams;
    private final JdbcTemplate jdbc;
    private final OutboxEventWriter outbox;
    private final ObjectMapper mapper;
    private final Clock clock;

    /** 신규 편입과 같은 가드 행을 잠그고 원래 준비 결과를 보존한다. */
    @Transactional
    public GuardPreparationResponse prepare(String type, String resourceId, GuardPrepareRequest request) {
        long resource = id(resourceId);
        validateType(type, request.operationType());
        long expected = version(request.expectedSourceVersion());
        guards.lockAll(List.of(new Resource(type, resource)));
        var guard = guard(type, resource);
        String hash = hash(type, resource, request.operationId(), request.operationType(), expected);
        if ("STUDY".equals(type)) {
            var history = studyHistory(resource, request.operationId());
            if (history != null) {
                if (!hash.equals(history.hash())) {
                    throw new ContestException(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT);
                }
                var current = request.operationId().equals(guard.operationId());
                return preparation(
                    request.operationId(),
                    current ? guard.state() : history.state(),
                    current ? guard.result() : history.result(),
                    history.preparedAt(),
                    history.canceled(),
                    blockingStudies(resource),
                    current
                );
            }
        }
        if (request.operationId().equals(guard.operationId())) {
            if (!hash.equals(guard.hash())) {
                throw new ContestException(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
            return preparation(
                request.operationId(),
                guard.state(),
                guard.result(),
                guard.preparedAt(),
                List.of(),
                List.of(),
                true
            );
        }
        if ("PREPARED".equals(guard.state())) {
            throw new ContestException(ContestErrorCode.CONTEST_OPERATION_BLOCKED);
        }
        if (expected < guard.sourceVersion()) {
            throw new ContestException(ContestErrorCode.SOURCE_VERSION_CONFLICT);
        }
        if (!"PROBLEM".equals(type) && !"OPEN".equals(guard.state())) {
            throw new ContestException(ContestErrorCode.CONTEST_OPERATION_BLOCKED);
        }
        var now = now();
        if ("MEMBER".equals(type) && count(
            "SELECT COUNT(*) FROM exam_participants p JOIN exams e ON e.id=p.exam_id "
                + "WHERE p.member_id=? AND p.entered_at IS NOT NULL AND p.status<>'ABSENT' "
                + "AND e.status NOT IN ('FINALIZED','CANCELED')",
            resource
        ) > 0) {
            throw new ContestException(ContestErrorCode.WITHDRAWAL_BLOCKED_ACTIVE_EXAM);
        }
        if ("PROBLEM".equals(type) && count(
            "SELECT COUNT(*) FROM exam_problems p JOIN exams e ON e.id=p.exam_id "
                + "WHERE p.problem_id=? AND (e.status='SCHEDULED' OR (e.status='IN_PROGRESS' AND e.ends_at>=?))",
            resource,
            now
        ) > 0) {
            throw new ContestException(ContestErrorCode.PROBLEM_IN_ACTIVE_EXAM);
        }
        jdbc
            .update(
                "UPDATE contest_resource_guards SET operation_id=?,operation_type=?,expected_source_version=?,"
                    + "request_hash=?,prepared_from_state=state,state='PREPARED',"
                    + "operation_result='PENDING',prepared_at=?,"
                    + "version=version+1,updated_at=? WHERE resource_type=? AND resource_id=?",
                request.operationId(),
                request.operationType(),
                expected,
                hash,
                now,
                now,
                type,
                resource
            );
        var canceled = "STUDY".equals(type) ? cancelScheduled(resource, now) : List.<String>of();
        if ("STUDY".equals(type)) {
            jdbc
                .update(
                    "INSERT INTO contest_study_guard_preparations (study_id,operation_id,request_hash,"
                        + "canceled_exam_ids,state,operation_result,prepared_at,created_at) "
                        + "VALUES (?,?,?,?,'PREPARED','PENDING',?,?)",
                    resource,
                    request.operationId(),
                    hash,
                    mapper.writeValueAsString(canceled),
                    now,
                    now
                );
        }
        return preparation(
            request.operationId(),
            "PREPARED",
            "PENDING",
            now,
            canceled,
            "STUDY".equals(type) ? blockingStudies(resource) : List.of(),
            true
        );
    }

    /** 잠금 뒤 작업과 버전을 재확인하고 완료·취소 결과를 멱등하게 보존한다. */
    @Transactional
    public GuardCompletionResponse finish(
        String type,
        String resourceId,
        GuardFinishRequest request,
        boolean complete
    ) {
        long resource = id(resourceId);
        long source = version(request.sourceVersion());
        validateResource(type);
        guards.lockAll(List.of(new Resource(type, resource)));
        var guard = guard(type, resource);
        return finishLocked(type, resource, request.operationId(), source, complete, guard);
    }

    /** 원격 조회 뒤 잠금을 다시 잡고 현재 작업과 기대 버전만 종결한다. */
    @Transactional
    public boolean reconcile(String type, long resource, String operationId, long expected, Result result) {
        if (!("STUDY".equals(type) || "PROBLEM".equals(type)) || !operationId.equals(result.operationId())
            || "PENDING".equals(result.state())) {
            return false;
        }
        if ("COMMITTED".equals(result.state())
            && ("STUDY".equals(type) && !"CLOSED".equals(result.resourceStatus()) || "PROBLEM".equals(type)
                && !("PRIVATE".equals(result.resourceStatus()) || "ARCHIVED".equals(result.resourceStatus())))) {
            return false;
        }
        guards.lockAll(List.of(new Resource(type, resource)));
        var current = guard(type, resource);
        if (!operationId.equals(current.operationId()) || current.expectedVersion() == null
            || ("STUDY".equals(type) ? !"STUDY_CLOSE".equals(current.operationType())
                : !"PROBLEM_HIDE".equals(current.operationType()))
            || current.expectedVersion() != expected || !"PREPARED".equals(current.state())
            || !"PENDING".equals(current.result())) {
            return false;
        }
        boolean complete = "COMMITTED".equals(result.state());
        if (!complete && !"ABORTED".equals(result.state())) {
            return false;
        }
        finishLocked(type, resource, operationId, version(result.sourceVersion()), complete, current);
        return true;
    }

    /** 스터디 종료 이벤트의 작업 ID와 버전이 현재 준비와 일치할 때 완료를 복구한다. */
    @Transactional
    public void studyClosed(long studyId, String operationId, long sourceVersion) {
        guards.lockAll(List.of(new Resource("STUDY", studyId)));
        var current = guard("STUDY", studyId);
        if (operationId != null && operationId.equals(current.operationId())
            && "STUDY_CLOSE".equals(current.operationType()) && "PREPARED".equals(current.state())
            && "PENDING".equals(current.result()) && current.expectedVersion() != null
            && sourceVersion > current.expectedVersion()) {
            finishLocked("STUDY", studyId, operationId, sourceVersion, true, current);
        } else if (!"PREPARED".equals(current.state()) && sourceVersion > current.sourceVersion()) {
            jdbc
                .update(
                    "UPDATE contest_resource_guards SET state='BLOCKED',source_version=?,"
                        + "version=version+1,updated_at=? WHERE resource_type='STUDY' AND resource_id=?",
                    sourceVersion,
                    now(),
                    studyId
                );
        }
    }

    private GuardCompletionResponse finishLocked(
        String type,
        long resource,
        String operationId,
        long source,
        boolean complete,
        Guard guard
    ) {
        if (!operationId.equals(guard.operationId())) {
            throw new ContestException(ContestErrorCode.OPERATION_NOT_CURRENT);
        }
        String outcome = complete ? "COMMITTED" : "ABORTED";
        if (guard.expectedVersion() == null
            || (complete ? source <= guard.expectedVersion() : source != guard.expectedVersion())
            || (complete && "PROBLEM".equals(type)
                && (guard.expectedVersion() == Long.MAX_VALUE || source != guard.expectedVersion() + 1))) {
            throw new ContestException(ContestErrorCode.SOURCE_VERSION_CONFLICT);
        }
        if (outcome.equals(guard.result())) {
            return new GuardCompletionResponse(
                operationId,
                guard.state(),
                outcome,
                Long.toString(guard.sourceVersion())
            );
        }
        if (!"PENDING".equals(guard.result()) || !"PREPARED".equals(guard.state())) {
            throw new ContestException(ContestErrorCode.OPERATION_ALREADY_COMPLETED);
        }
        if (complete && "STUDY".equals(type) && !blockingStudies(resource).isEmpty()) {
            throw new ContestException(ContestErrorCode.CONTEST_OPERATION_BLOCKED);
        }
        String next = complete ? "BLOCKED" : guard.previousState();
        if ("PROBLEM".equals(type) && guard.sourceVersion() > (complete ? source : guard.expectedVersion())) {
            next = "GENERAL".equals(guard.scope()) && "PUBLIC".equals(guard.status()) ? "OPEN" : "BLOCKED";
        }
        long latest = Math.max(guard.sourceVersion(), source);
        jdbc
            .update(
                "UPDATE contest_resource_guards SET state=?,operation_result=?,source_version=?,"
                    + "version=version+1,updated_at=? WHERE resource_type=? AND resource_id=?",
                next,
                outcome,
                latest,
                now(),
                type,
                resource
            );
        if ("STUDY".equals(type)) {
            jdbc
                .update(
                    "UPDATE contest_study_guard_preparations SET state=?,operation_result=? "
                        + "WHERE study_id=? AND operation_id=?",
                    next,
                    outcome,
                    resource,
                    operationId
                );
        }
        return new GuardCompletionResponse(operationId, next, outcome, Long.toString(latest));
    }

    private List<String> cancelScheduled(long studyId, LocalDateTime timestamp) {
        var ids
            = jdbc
                .queryForList(
                    "SELECT id FROM exams WHERE study_id=? AND status='SCHEDULED' ORDER BY id",
                    Long.class,
                    studyId
                );
        var canceled = new ArrayList<String>();
        for (long id : ids) {
            var exam = exams.findForUpdate(id).orElseThrow();
            if (exam.getStatus() != ExamStatus.SCHEDULED || !timestamp.isBefore(exam.getStartsAt())) {
                continue;
            }
            var members
                = jdbc
                    .queryForList(
                        "SELECT member_id FROM exam_participants WHERE exam_id=? "
                            + "AND status<>'CANCELED' ORDER BY id FOR UPDATE",
                        Long.class,
                        id
                    );
            var canceledAt = now();
            if (!canceledAt.isBefore(exam.getStartsAt())) {
                continue;
            }
            exam.cancel(canceledAt);
            exams.saveAndFlush(exam);
            outbox
                .append(
                    "EXAM",
                    "ExamCanceled",
                    "v1",
                    Long.toString(id),
                    "contest-" + UUID.randomUUID(),
                    new CanceledPayload(
                        Long.toString(id),
                        Long.toString(studyId),
                        Integer.toString(exam.getExamRevision()),
                        DateTimeFormatter
                            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSxxx")
                            .format(canceledAt.atZone(ClockConfiguration.SERVICE_ZONE)),
                        STUDY_CANCELLATION_REASON,
                        members.stream().map(String::valueOf).toList()
                    )
                );
            canceled.add(Long.toString(id));
        }
        return List.copyOf(canceled);
    }

    private List<String> blockingStudies(long studyId) {
        return jdbc
            .queryForList(
                "SELECT CAST(id AS CHAR) FROM exams WHERE study_id=? "
                    + "AND status NOT IN ('CLOSED','FINALIZED','CANCELED') ORDER BY id",
                String.class,
                studyId
            );
    }

    private GuardPreparationResponse preparation(
        String operationId,
        String state,
        String result,
        LocalDateTime preparedAt,
        List<String> canceled,
        List<String> blocking,
        boolean current
    ) {
        boolean ready = current && "PREPARED".equals(state) && blocking.isEmpty();
        return new GuardPreparationResponse(
            operationId,
            state,
            result,
            ready,
            preparedAt == null ? null : preparedAt.atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime(),
            canceled,
            blocking
        );
    }

    private Guard guard(String type, long resource) {
        return jdbc
            .queryForObject(
                "SELECT operation_id,request_hash,expected_source_version,state,prepared_from_state,"
                    + "operation_result,source_version,source_scope,source_status,prepared_at,operation_type "
                    + "FROM contest_resource_guards " + "WHERE resource_type=? AND resource_id=? FOR UPDATE",
                (rs, row) -> new Guard(
                    rs.getString(1),
                    rs.getString(2),
                    rs.getObject(3, Long.class),
                    rs.getString(4),
                    rs.getString(5),
                    rs.getString(6),
                    rs.getLong(7),
                    rs.getString(8),
                    rs.getString(9),
                    rs.getObject(10, LocalDateTime.class),
                    rs.getString(11)
                ),
                type,
                resource
            );
    }

    private StudyHistory studyHistory(long study, String operation) {
        var rows
            = jdbc
                .query(
                    "SELECT request_hash,canceled_exam_ids,state,operation_result,prepared_at "
                        + "FROM contest_study_guard_preparations WHERE study_id=? AND operation_id=?",
                    (rs, row) -> new StudyHistory(
                        rs.getString(1),
                        Arrays.asList(mapper.readValue(rs.getString(2), String[].class)),
                        rs.getString(3),
                        rs.getString(4),
                        rs.getObject(5, LocalDateTime.class)
                    ),
                    study,
                    operation
                );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private long count(String sql, Object... args) {
        Long result = jdbc.queryForObject(sql, Long.class, args);
        return result == null ? 0 : result;
    }

    private void validateType(String type, String operation) {
        if (!("MEMBER".equals(type) && "WITHDRAWAL".equals(operation)
            || "STUDY".equals(type) && "STUDY_CLOSE".equals(operation)
            || "PROBLEM".equals(type) && "PROBLEM_HIDE".equals(operation))) {
            throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
    }

    private void validateResource(String type) {
        if (!("MEMBER".equals(type) || "STUDY".equals(type) || "PROBLEM".equals(type))) {
            throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
    }

    private String hash(String type, long id, String operationId, String operationType, long expected) {
        try {
            var value = mapper.writeValueAsString(new HashInput(type, id, operationId, operationType, expected));
            return HexFormat
                .of()
                .formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException failure) {
            throw new IllegalStateException(failure);
        }
    }

    private long id(String value) {
        long parsed = version(value);
        if (parsed == 0 || !Long.toString(parsed).equals(value)) {
            throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        return parsed;
    }

    private long version(String value) {
        try {
            if (value != null && value.matches("[0-9]+")) {
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

    private record HashInput(String type, long id, String operationId, String operationType, long expected) {
    }
    private record Guard(
        String operationId,
        String hash,
        Long expectedVersion,
        String state,
        String previousState,
        String result,
        long sourceVersion,
        String scope,
        String status,
        LocalDateTime preparedAt,
        String operationType
    ) {
    }
    private record StudyHistory(
        String hash,
        List<String> canceled,
        String state,
        String result,
        LocalDateTime preparedAt
    ) {
    }
}
