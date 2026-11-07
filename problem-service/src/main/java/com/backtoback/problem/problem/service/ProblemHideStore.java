package com.backtoback.problem.problem.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.api.ApiErrorResponse;
import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.client.ContestGuardClient.Rejection;
import com.backtoback.problem.problem.domain.Problem;
import com.backtoback.problem.problem.domain.ProblemStateOperation;
import com.backtoback.problem.problem.domain.ProblemVisibility;
import com.backtoback.problem.problem.dto.response.ProblemPublicationResponse;
import com.backtoback.problem.problem.repository.ProblemRepository;
import com.backtoback.problem.problem.repository.ProblemStateOperationRepository;
import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** 문제 잠금 아래 가드 작업의 원래 입력과 최종 상태를 확정한다. */
@Service
@RequiredArgsConstructor
public class ProblemHideStore {
    private final ProblemRepository problems;
    private final ProblemStateOperationRepository operations;
    private final JdbcTemplate jdbc;
    private final OutboxEventWriter outbox;
    private final ObjectMapper mapper;
    private final Clock clock;

    /**
     * 원격 준비 전에 원래 작업을 영속 보존한다.
     *
     * @param command 정규화 요청
     * @return 신규 또는 동일 키의 원래 작업
     * @throws ProblemException 문제 없음은 {@code PROBLEM_NOT_FOUND}, 작성자 불일치는 {@code AUTH_ACCESS_DENIED},
     *     다른 본문의 동일 키는 {@code IDEMPOTENCY_KEY_CONFLICT}, 다른 미종결 작업은 {@code PROBLEM_VERSION_CONFLICT}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProblemStateOperation begin(Command command) {
        var problem = lock(command.problemId());
        if (!problem.getAuthorId().equals(command.actorId())) {
            throw new ProblemException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        var type = command.target() == ProblemVisibility.PRIVATE ? "UNPUBLICATION" : "ARCHIVE";
        var existing
            = operations
                .findByActorIdAndProblemIdAndOperationTypeAndIdempotencyKey(
                    command.actorId(),
                    command.problemId(),
                    type,
                    command.key()
                );
        if (existing.isPresent()) {
            if (!existing.get().getRequestHash().equals(command.hash())) {
                throw new ProblemException(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
            return existing.get();
        }
        if (operations.existsByProblemIdAndState(command.problemId(), "PENDING")) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_VERSION_CONFLICT);
        }
        var operation
            = ProblemStateOperation
                .hide(
                    UUID.randomUUID().toString(),
                    problem,
                    command.key(),
                    command.hash(),
                    command.version(),
                    command.target(),
                    command.reason()
                );
        try {
            problem.validateHide(command.version(), command.target());
        } catch (ProblemException rejected) {
            abort(operation, rejected);
        }
        return operations.saveAndFlush(operation);
    }

    /**
     * 준비 성공 뒤 상태·감사·이벤트·최초 응답을 함께 확정한다.
     *
     * @param operationId 원래 작업 ID
     * @param command 원래 요청
     * @param rejection 원격 확정 거절, 준비 성공이면 null
     * @return 영속 종결 결과
     * @throws ProblemException 문제 없음은 {@code PROBLEM_NOT_FOUND}
     * @throws java.util.NoSuchElementException 문제에 속하는 원래 작업이 없는 경우
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProblemStateOperation finish(String operationId, Command command, Rejection rejection) {
        var problem = lock(command.problemId());
        var operation = operations.findByProblemIdAndOperationId(command.problemId(), operationId).orElseThrow();
        if (!"PENDING".equals(operation.getState())) {
            return operation;
        }
        if (rejection != null) {
            operation.abort(rejection.status(), rejection.json());
            return operation;
        }
        try {
            problem
                .hide(operation.getExpectedSourceVersion(), ProblemVisibility.valueOf(operation.getTargetVisibility()));
        } catch (ProblemException rejected) {
            abort(operation, rejected);
            return operation;
        }
        var message = command.target() == ProblemVisibility.PRIVATE ? "문제가 비공개로 전환되었습니다." : "문제가 보관되었습니다.";
        operation
            .commit(
                problem.getRowVersion(),
                mapper.writeValueAsString(ApiResponse.success(message, ProblemPublicationResponse.from(problem)))
            );
        var revisions = jdbc.query("""
            SELECT r.id,r.revision_number FROM problem_revisions r WHERE r.problem_id=? AND EXISTS
            (SELECT 1 FROM problem_validations v WHERE v.problem_revision_id=r.id AND v.result_status='PASSED')
            ORDER BY r.revision_number DESC LIMIT 1
            """, (rs, row) -> new Revision(rs.getLong(1), rs.getInt(2)), problem.getId());
        var revision = revisions.isEmpty() ? null : revisions.getFirst();
        var languages = revision == null ? List.<String>of() : jdbc.queryForList("""
            SELECT DISTINCT language FROM problem_validations WHERE problem_revision_id=?
            AND result_status='PASSED' ORDER BY language
            """, String.class, revision.id());
        var state
            = new State(
                problem.getId().toString(),
                problem.getRowVersion(),
                problem.getScope().name(),
                problem.getVisibility().name(),
                revision == null ? null : Long.toString(revision.id()),
                revision == null ? null : revision.number(),
                languages,
                "VISIBILITY",
                OffsetDateTime.now(clock),
                operationId
            );
        outbox.append("PROBLEM", "ProblemStateChanged", "v2", state.problemId(), command.correlationId(), state);
        jdbc
            .update(
                """
                        INSERT INTO audit_logs(actor_id,action,target_type,target_id,before_state,after_state,
                    reason,client_ip,created_at)
                        VALUES(?,?,'PROBLEM',?,?,?,?,?,?)
                        """,
                command.actorId(),
                "PROBLEM_" + operation.getOperationType(),
                state.problemId(),
                mapper
                    .writeValueAsString(
                        new ProblemPublicationResponse(operation.getBeforeVisibility(), command.version())
                    ),
                mapper.writeValueAsString(ProblemPublicationResponse.from(problem)),
                operation.getReason(),
                command.clientIp(),
                LocalDateTime.now(clock)
            );
        return operation;
    }

    /**
     * 최신 커밋을 읽도록 문제부터 잠근다.
     *
     * @param id 문제 ID
     * @return 잠근 문제
     * @throws ProblemException 문제 없음은 {@code PROBLEM_NOT_FOUND}
     */
    private Problem lock(long id) {
        return problems
            .findByIdForUpdate(id)
            .orElseThrow(() -> new ProblemException(ProblemErrorCode.PROBLEM_NOT_FOUND));
    }

    /**
     * 확정 업무 오류의 최초 응답을 보존한다.
     *
     * @param operation 미종결 작업
     * @param rejected 확정 오류
     */
    private void abort(ProblemStateOperation operation, ProblemException rejected) {
        operation
            .abort(
                rejected.getErrorCode().getStatus().value(),
                mapper.writeValueAsString(ApiErrorResponse.of(rejected.getErrorCode()))
            );
    }

    /** 원래 입력과 추적 정보다. */
    public record Command(
        long problemId,
        long actorId,
        String key,
        String hash,
        int version,
        ProblemVisibility target,
        String reason,
        String correlationId,
        String clientIp
    ) {
    }
    /** 사용 회차 식별자다. */
    private record Revision(long id, int number) {
    }
    /** P1 v2 상태 스냅샷이다. */
    private record State(
        String problemId,
        int sourceVersion,
        String scope,
        String visibility,
        String problemRevisionId,
        Integer revisionNumber,
        List<String> supportedLanguages,
        String changeType,
        @JsonFormat(pattern = "uuuu-MM-dd'T'HH:mm:ss.SSSSSSxxx") OffsetDateTime changedAt,
        String operationId
    ) {
    }
}
