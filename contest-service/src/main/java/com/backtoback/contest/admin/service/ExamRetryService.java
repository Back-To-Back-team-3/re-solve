package com.backtoback.contest.admin.service;

import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientResponseException;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.common.web.security.user.UserRole;
import com.backtoback.contest.admin.dto.ContestOperationResponse;
import com.backtoback.contest.admin.dto.ExamRetryRequest;
import com.backtoback.contest.admin.dto.ExamRetryResponse;
import com.backtoback.contest.admin.service.ExamRetryStore.Operation;
import com.backtoback.contest.exam.client.ExamDependencyClient;
import com.backtoback.contest.exam.client.ExamRetryClient;
import com.backtoback.contest.exam.repository.ExamRepository;
import com.backtoback.contest.exam.service.MemberReplicaService;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** 현재 운영 권한을 확인하고 잠금 밖에서 Judge 재처리를 요청한다. */
@Service
@RequiredArgsConstructor
public class ExamRetryService {
    private static final Logger log = LoggerFactory.getLogger(ExamRetryService.class);
    private final ExamRepository exams;
    private final ExamDependencyClient dependencies;
    private final MemberReplicaService members;
    private final ExamRetryStore store;
    private final ExamRetryClient client;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /** 작업 선저장과 원격 호출은 서로 다른 트랜잭션 경계에 둔다. */
    public ExamRetryResponse request(String examId, String receiptId, GatewayUser user, ExamRetryRequest request) {
        long exam = id(examId);
        long receipt = id(receiptId);
        long actor = id(user.userId());
        authorize(exam, user, true);
        var operation = store.prepare(exam, receipt, actor, request);
        if ("PENDING".equals(operation.state())) {
            operation = reconcile(operation);
        }
        return response(operation);
    }

    /** ADMIN은 모든 작업 상태를 조회한다. */
    public ContestOperationResponse adminOperation(String operationId, GatewayUser user) {
        if (user.role() != UserRole.ADMIN) {
            throw new ContestException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        return operationResponse(store.require(operationId));
    }

    /** 현재 운영자 본인의 해당 시험 재처리 작업만 조회한다. */
    public ContestOperationResponse examOperation(String examId, String operationId, GatewayUser user) {
        long exam = id(examId);
        authorize(exam, user, false);
        var operation = store.require(operationId);
        if (!"EXAM".equals(operation.context()) || !"EXAM_RETRY".equals(operation.type()) || operation.examId() != exam
            || operation.actorId() != id(user.userId())) {
            throw new ContestException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        return operationResponse(operation);
    }

    /** 같은 작업으로 재호출하며 미확인 응답은 PENDING을 유지한다. */
    public Operation reconcile(Operation operation) {
        if (!"PENDING".equals(operation.state()) || !"EXAM_RETRY".equals(operation.type())
            || !"EXAM".equals(operation.context())) {
            return operation;
        }
        Long submission = submission(operation.examId(), operation.receiptId());
        if (submission == null) {
            return operation;
        }
        ExamRetryClient.Result result;
        try {
            result
                = client
                    .retry(
                        submission,
                        new ExamRetryClient.Request(
                            "EXAM",
                            operation.receiptId().toString(),
                            operation.expectedAttempt(),
                            operation.id(),
                            Long.toString(operation.actorId()),
                            operation.reason()
                        )
                    );
        } catch (RestClientResponseException failure) {
            String code = errorCode(failure);
            if (failure.getStatusCode().value() == 409 && code != null) {
                return store.rejected(operation.id(), code);
            }
            log.warn("시험 재처리 응답 미확인: operationId={}, status={}", operation.id(), failure.getStatusCode().value());
            return store.noteUnknown(operation.id(), code);
        } catch (RuntimeException failure) {
            log
                .warn(
                    "시험 재처리 응답 미확인: operationId={}, exceptionType={}",
                    operation.id(),
                    failure.getClass().getSimpleName()
                );
            return store.noteUnknown(operation.id(), null);
        }
        return store.succeeded(operation.id(), result.judgeAttempt());
    }

    private void authorize(long examId, GatewayUser user, boolean allowAdmin) {
        var exam = exams.findById(examId).orElseThrow(() -> new ContestException(ContestErrorCode.EXAM_NOT_FOUND));
        if (allowAdmin && user.role() == UserRole.ADMIN) {
            return;
        }
        long member = id(user.userId());
        if (!"ACTIVE".equals(members.refresh(member))) {
            throw new ContestException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        var study = dependencies.study(exam.getStudyId());
        if (!Set.of("ACTIVE", "RECRUITING", "CLOSED").contains(study.studyStatus())) {
            throw new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
        if (study
            .members()
            .stream()
            .noneMatch(
                item -> user.userId().equals(item.memberId())
                    && ("LEADER".equals(item.role()) || "MANAGER".equals(item.role()))
            )) {
            throw new ContestException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
    }

    private ExamRetryResponse response(Operation operation) {
        var rows
            = jdbc
                .query(
                    "SELECT submission_id,judge_attempt FROM exam_submissions WHERE exam_id=? AND id=?",
                    (rs, row) -> new Receipt(rs.getObject(1, Long.class), rs.getObject(2, Integer.class)),
                    operation.examId(),
                    operation.receiptId()
                );
        if (rows.isEmpty()) {
            throw new ContestException(ContestErrorCode.EXAM_SUBMISSION_NOT_FOUND);
        }
        var receipt = rows.getFirst();
        return new ExamRetryResponse(
            operation.id(),
            operation.state(),
            operation.receiptId().toString(),
            receipt.submissionId() == null ? null : receipt.submissionId().toString(),
            receipt.attempt() == null ? 0 : receipt.attempt(),
            operation.targetAttempt()
        );
    }

    private ContestOperationResponse operationResponse(Operation operation) {
        return new ContestOperationResponse(
            operation.id(),
            operation.type(),
            operation.context(),
            Long.toString(operation.examId()),
            operation.receiptId() == null ? null : operation.receiptId().toString(),
            operation.state(),
            operation.expectedAttempt(),
            operation.targetAttempt(),
            operation.lastError(),
            operation.updatedAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime()
        );
    }

    private Long submission(long examId, Long receiptId) {
        var rows
            = jdbc
                .query(
                    "SELECT submission_id FROM exam_submissions WHERE exam_id=? AND id=?",
                    (rs, row) -> rs.getObject(1, Long.class),
                    examId,
                    receiptId
                );
        return rows.isEmpty() ? null : rows.getFirst();
    }

    private String errorCode(RestClientResponseException failure) {
        try {
            var body = mapper.readTree(failure.getResponseBodyAsString());
            var error = body.path("error").path("code");
            if (body.path("success").isBoolean() && !body.path("success").asBoolean() && error.isString()
                && !error.asString().isBlank() && error.asString().length() <= 100) {
                return error.asString();
            }
            return null;
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private long id(String value) {
        try {
            long parsed = Long.parseLong(value);
            if (parsed > 0 && Long.toString(parsed).equals(value)) {
                return parsed;
            }
        } catch (NumberFormatException ignored) {
            // 식별자는 양의 10진 문자열이다.
        }
        throw new ContestException(CommonErrorCode.COMMON_INVALID_REQUEST);
    }

    private record Receipt(Long submissionId, Integer attempt) {
    }
}
