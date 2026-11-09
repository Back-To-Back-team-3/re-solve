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
    private final ContestGuardHistory history;

    /**
     * 신규 편입과 같은 가드 행을 잠그고 기존 사용을 검사한다.
     * 스터디는 예정 시험의 취소와 최초 목록을 함께 저장하며, 반복 요청에서도 현재 종료 대상을 다시 확인한다.
     *
     * @param type 소유 서비스 자원 종류
     * @param resourceId 소유 서비스의 양의 자원 ID
     * @param request 원래 작업 ID·유형과 기대 원천 버전
     * @return 준비·종결 상태와 스터디의 취소 및 대기 시험 목록
     * @throws ContestException 입력 오류, 동일 ID의 다른 요청, 다른 작업 점유, 오래된 원천 버전 또는 사용 중 자원인 경우
     */
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
        if (!"STUDY".equals(type)) {
            var historical = history.find(type, resource, request.operationId());
            if (historical != null) {
                if (!hash.equals(historical.hash())) {
                    throw new ContestException(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT);
                }
                return preparation(
                    request.operationId(),
                    historical.state(),
                    historical.operationResult(),
                    historical.preparedAt(),
                    List.of(),
                    List.of(),
                    false
                );
            }
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
                    + "operation_result='PENDING',prepared_at=?,last_reconciliation_attempt_at=NULL,"
                    + "prepared_snapshot_version=snapshot_version,"
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

    /**
     * 잠금 뒤 현재 작업과 원천 버전을 검증해 완료 또는 취소 결과를 보존한다.
     * 문제 완료는 기대 버전의 다음 버전만 허용하고 더 최신 공개 상태를 유지한다.
     *
     * @param type 소유 서비스 자원 종류
     * @param resourceId 소유 서비스 자원 ID
     * @param request 작업 ID와 소유 서비스의 결과 버전
     * @param complete 완료면 {@code true}, 확정 취소면 {@code false}
     * @return 현재 가드 상태와 보존된 최대 원천 버전
     * @throws ContestException 작업 ID·버전 불일치, 반대 결과로 종결 또는 스터디 종료 대기 중인 경우
     */
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
        return finishLocked(
            type,
            resource,
            request.operationId(),
            source,
            complete,
            guard,
            ContestGuardHistory.Path.DIRECT,
            request.reason()
        );
    }

    /**
     * 원격 조회 뒤 잠금을 다시 잡고 현재 작업과 기대 버전만 종결한다.
     * 조회 중 다른 작업이 준비됐거나 원격 결과가 미확인이면 상태를 바꾸지 않는다.
     *
     * @param type 스터디 또는 문제 자원 종류
     * @param resource 소유 서비스 자원 ID
     * @param operationId 조회한 원래 작업 ID
     * @param expected 조회 전에 관찰한 기대 원천 버전
     * @param result 원격 서비스가 보존한 작업 결과
     * @return 현재 작업에 완료·취소 결과를 반영했는지 여부
     * @throws ContestException 결과 버전이 계약과 다르거나 스터디 종료를 기다리는 경우
     */
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
        finishLocked(
            type,
            resource,
            operationId,
            version(result.sourceVersion()),
            complete,
            current,
            ContestGuardHistory.Path.RECONCILIATION,
            null
        );
        return true;
    }

    /**
     * 스터디 종료 이벤트가 현재 준비 작업과 일치하면 완료를 복구한다.
     * 다른 준비 작업은 유지하면서 종료 사실을 저장해 뒤늦은 취소가 신규 생성을 열지 않게 한다.
     *
     * @param studyId 종료된 스터디 ID
     * @param operationId 이벤트의 작업 ID. 없는 경우 {@code null}
     * @param sourceVersion 스터디의 종료 버전
     * @throws ContestException 같은 작업이지만 아직 시험 종료를 기다리는 경우
     */
    @Transactional
    public void studyClosed(long studyId, String operationId, long sourceVersion) {
        guards.lockAll(List.of(new Resource("STUDY", studyId)));
        var current = guard("STUDY", studyId);
        if (operationId != null && operationId.equals(current.operationId())
            && "STUDY_CLOSE".equals(current.operationType()) && "PREPARED".equals(current.state())
            && "PENDING".equals(current.result()) && current.expectedVersion() != null
            && sourceVersion > current.expectedVersion()) {
            finishLocked(
                "STUDY",
                studyId,
                operationId,
                sourceVersion,
                true,
                current,
                ContestGuardHistory.Path.EVENT,
                null
            );
        } else if (sourceVersion > current.sourceVersion()
            || (sourceVersion == current.sourceVersion() && !"CLOSED".equals(current.status()))) {
            jdbc
                .update(
                    "UPDATE contest_resource_guards SET state=IF(state='PREPARED',state,'BLOCKED'),source_version=?,"
                        + "source_status='CLOSED',version=version+1,updated_at=? "
                        + "WHERE resource_type='STUDY' AND resource_id=?",
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
        Guard guard,
        ContestGuardHistory.Path path,
        String reason
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
        if ("PROBLEM".equals(type) && guard.snapshotVersion() != null
            && guard.snapshotVersion() >= guard.sourceVersion()
            && (complete ? guard.snapshotVersion() > source
                : guard.snapshotVersion() >= guard.expectedVersion() && (guard.preparedSnapshotVersion() == null
                    || guard.snapshotVersion() > guard.preparedSnapshotVersion()))) {
            next = "GENERAL".equals(guard.scope()) && "PUBLIC".equals(guard.status()) ? "OPEN" : "BLOCKED";
        }
        if (!complete && "STUDY".equals(type) && "CLOSED".equals(guard.status())) {
            next = "BLOCKED";
        }
        if (!complete && "MEMBER".equals(type) && guard.sourceVersion() > guard.expectedVersion()) {
            next = "BLOCKED";
        }
        // 문제 취소 버전으로 회차·언어 스냅샷의 버전을 올리면 이후 같은 버전의 이벤트가 무시된다.
        long latest
            = !complete && "PROBLEM".equals(type) ? guard.sourceVersion() : Math.max(guard.sourceVersion(), source);
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
        history.record(type, resource, path, reason, now());
        return new GuardCompletionResponse(operationId, next, outcome, Long.toString(latest));
    }

    /** 시험 잠금과 참가자 잠금 뒤 시작 시각을 재검증해 취소와 Outbox를 함께 저장한다. */
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

    /** 스케줄러 전이가 지연된 시험도 종단 상태가 될 때까지 완료를 보류한다. */
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
                    + "operation_result,source_version,source_scope,source_status,prepared_at,operation_type,"
                    + "snapshot_version,prepared_snapshot_version " + "FROM contest_resource_guards "
                    + "WHERE resource_type=? AND resource_id=? FOR UPDATE",
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
                    rs.getString(11),
                    rs.getObject(12, Long.class),
                    rs.getObject(13, Long.class)
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

    /** 표현이 다른 숫자 문자열도 같은 원천 버전으로 정규화해 요청 해시를 만든다. */
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
        String operationType,
        Long snapshotVersion,
        Long preparedSnapshotVersion
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
