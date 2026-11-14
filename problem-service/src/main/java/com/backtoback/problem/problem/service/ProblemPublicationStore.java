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
import com.backtoback.problem.problem.domain.ProblemStateOperation;
import com.backtoback.problem.problem.dto.response.ProblemPublicationResponse;
import com.backtoback.problem.problem.repository.ProblemRepository;
import com.backtoback.problem.problem.repository.ProblemStateOperationRepository;
import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** 공개 상태·최초 응답·감사·이벤트를 문제 잠금 아래 원자적으로 확정한다. */
@Service
@RequiredArgsConstructor
public class ProblemPublicationStore {
    private final ProblemRepository problems;
    private final ProblemStateOperationRepository operations;
    private final JdbcTemplate jdbc;
    private final OutboxEventWriter outbox;
    private final ObjectMapper mapper;
    private final Clock clock;

    /**
     * 동일 문제 요청을 직렬화하고 최초 공개 성공 또는 확정 실패를 저장한다.
     * 새 트랜잭션의 첫 조회에서 문제를 잠가 상위 트랜잭션의 오래된 스냅샷을 사용하지 않는다.
     * 확정 업무 오류는 결과로 반환하여 {@code ABORTED} 저장을 커밋한다.
     *
     * @param command 검증한 공개 요청
     * @return 최초 작업의 영속 결과
     * @throws ProblemException 없는 문제는 {@code PROBLEM_NOT_FOUND}, 작성자 불일치는 {@code AUTH_ACCESS_DENIED},
     *     같은 키의 다른 요청은 {@code IDEMPOTENCY_KEY_CONFLICT}, 미종결 작업 충돌은 {@code PROBLEM_VERSION_CONFLICT}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProblemStateOperation publish(Command command) {
        var problem
            = problems
                .findByIdForUpdate(command.problemId())
                .orElseThrow(() -> new ProblemException(ProblemErrorCode.PROBLEM_NOT_FOUND));
        if (!problem.getAuthorId().equals(command.actorId())) {
            throw new ProblemException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        var existing
            = operations
                .findByActorIdAndProblemIdAndOperationTypeAndIdempotencyKey(
                    command.actorId(),
                    command.problemId(),
                    "PUBLICATION",
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
                .publication(
                    UUID.randomUUID().toString(),
                    problem,
                    command.key(),
                    command.hash(),
                    command.rowVersion()
                );
        var now = LocalDateTime.now(clock);
        var revisions = jdbc.query("""
            SELECT r.id, r.revision_number FROM problem_revisions r
            WHERE r.problem_id=? AND EXISTS (
                SELECT 1 FROM problem_validations v WHERE v.problem_revision_id=r.id AND v.result_status='PASSED')
            ORDER BY r.revision_number DESC LIMIT 1
            """, (rs, index) -> new Revision(rs.getLong("id"), rs.getInt("revision_number")), problem.getId());
        try {
            if (problem.getRowVersion() != command.rowVersion()) {
                throw new ProblemException(ProblemErrorCode.PROBLEM_VERSION_CONFLICT);
            }
            if (revisions.isEmpty()) {
                throw new ProblemException(ProblemErrorCode.PROBLEM_INVALID_STATE_TRANSITION);
            }
            problem.publish(command.rowVersion(), now);
        } catch (ProblemException rejected) {
            operation
                .abort(
                    rejected.getErrorCode().getStatus().value(),
                    mapper.writeValueAsString(ApiErrorResponse.of(rejected.getErrorCode()))
                );
            return operations.saveAndFlush(operation);
        }
        operation
            .commit(
                problem.getRowVersion(),
                mapper.writeValueAsString(ApiResponse.success("문제가 공개되었습니다.", ProblemPublicationResponse.from(problem)))
            );
        var revision = revisions.getFirst();
        var languages = jdbc.queryForList("""
            SELECT DISTINCT language FROM problem_validations
            WHERE problem_revision_id=? AND result_status='PASSED' ORDER BY language
            """, String.class, revision.id());
        var state
            = new State(
                problem.getId().toString(),
                problem.getRowVersion(),
                problem.getScope().name(),
                problem.getVisibility().name(),
                Long.toString(revision.id()),
                revision.number(),
                languages,
                "VISIBILITY",
                OffsetDateTime.now(clock),
                null
            );
        outbox.append("PROBLEM", "ProblemStateChanged", "v2", state.problemId(), command.correlationId(), state);
        jdbc
            .update(
                """
                    INSERT INTO audit_logs (actor_id, action, target_type, target_id, before_state, after_state,
                        reason, client_ip, created_at) VALUES (?, 'PROBLEM_PUBLICATION', 'PROBLEM', ?, ?, ?, ?, ?, ?)
                    """,
                command.actorId(),
                state.problemId(),
                mapper
                    .writeValueAsString(
                        new ProblemPublicationResponse(operation.getBeforeVisibility(), command.rowVersion())
                    ),
                mapper.writeValueAsString(ProblemPublicationResponse.from(problem)),
                operation.getReason(),
                command.clientIp(),
                now
            );
        return operations.saveAndFlush(operation);
    }

    /** 공개 요청의 원래 입력과 추적 정보다. */
    public record Command(
        long problemId,
        long actorId,
        String key,
        String hash,
        int rowVersion,
        String correlationId,
        String clientIp
    ) {
    }

    /** 통과 언어가 있는 최신 사용 회차다. */
    private record Revision(long id, int number) {
    }

    /** 공개 상태 변경 시 발행하는 P1 v2 본문이다. */
    private record State(
        String problemId,
        int sourceVersion,
        String scope,
        String visibility,
        String problemRevisionId,
        int revisionNumber,
        List<String> supportedLanguages,
        String changeType,
        @JsonFormat(pattern = "uuuu-MM-dd'T'HH:mm:ss.SSSSSSxxx") OffsetDateTime changedAt,
        String operationId
    ) {
    }
}
