package com.backtoback.problem.problem.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.domain.Problem;
import com.backtoback.problem.problem.domain.ProblemRevision;
import com.backtoback.problem.problem.domain.ProblemValidation;
import com.backtoback.problem.problem.domain.ProblemVisibility;
import com.backtoback.problem.problem.dto.response.ProblemValidationResponse;
import com.backtoback.problem.problem.repository.ProblemCodeAssetRepository;
import com.backtoback.problem.problem.repository.ProblemRepository;
import com.backtoback.problem.problem.repository.ProblemRevisionRepository;
import com.backtoback.problem.problem.repository.ProblemValidationRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** 문제·회차·코드 잠금 아래 요청·감사·Outbox를 함께 확정한다. */
@Service
@RequiredArgsConstructor
public class ProblemValidationStore {
    private final ProblemRepository problems;
    private final ProblemRevisionRepository revisions;
    private final ProblemCodeAssetRepository codes;
    private final ProblemValidationRepository validations;
    private final EntityManager entityManager;
    private final OutboxEventWriter events;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    /**
     * 원래 요청 해시를 비교하고 최초 응답을 복원한다.
     *
     * @param authorId 관리자
     * @param key 멱등키
     * @param hash 원래 요청 해시
     * @return 최초 응답 또는 빈 값
     * @throws ProblemException 동일 키의 다른 입력이면 {@code IDEMPOTENCY_KEY_CONFLICT}
     */
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        readOnly = true
    )
    public Optional<ProblemValidationResponse> replay(long authorId, String key, String hash) {
        return validations.findByRequestedByAndIdempotencyKey(authorId, key).map(value -> response(value, hash));
    }

    /**
     * 외부 업로드 전에 문제 소속·작성 권한·진행 상태를 검사한다.
     *
     * @param problemId 문제
     * @param revisionId 회차
     * @param authorId 관리자
     * @param language 언어
     * @throws ProblemException 대상이 없으면 {@code PROBLEM_NOT_FOUND} 또는 {@code PROBLEM_REVISION_NOT_FOUND},
     *     작성자가 아니면 {@code AUTH_ACCESS_DENIED}, 진행 중이면 {@code PROBLEM_VALIDATION_IN_PROGRESS},
     *     보관 또는 통과 상태이면 {@code PROBLEM_INVALID_STATE_TRANSITION}
     */
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        readOnly = true
    )
    public void validate(long problemId, long revisionId, long authorId, String language) {
        var problem
            = problems.findById(problemId).orElseThrow(() -> new ProblemException(ProblemErrorCode.PROBLEM_NOT_FOUND));
        var revision
            = revisions
                .findById(revisionId)
                .orElseThrow(() -> new ProblemException(ProblemErrorCode.PROBLEM_REVISION_NOT_FOUND));
        validateTarget(problem, revision, authorId, language);
    }

    /**
     * 업로드 완료 파일을 연결하고 검증 요청·감사·발행 예약을 원자 저장한다.
     * 문제 잠금 대기 후 재조회가 최신 커밋을 보도록 READ_COMMITTED를 사용한다.
     *
     * @param command 접수 고정 입력
     * @param assetId 업로드 완료 파일
     * @return 최초 접수 응답
     * @throws ProblemException 사전 검사 오류에 더해 파일 불일치는 {@code COMMON_INVALID_REQUEST},
     *     동일 키의 다른 입력은 {@code IDEMPOTENCY_KEY_CONFLICT}
     */
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED
    )
    public ProblemValidationResponse save(Command command, long assetId) {
        var existing = validations.findByRequestedByAndIdempotencyKey(command.authorId(), command.key());
        if (existing.isPresent()) {
            var result = response(existing.get(), command.hash());
            markUnreferenced(assetId);
            return result;
        }
        var problem
            = problems
                .findByIdForUpdate(command.problemId())
                .orElseThrow(() -> new ProblemException(ProblemErrorCode.PROBLEM_NOT_FOUND));
        var revision = entityManager.find(ProblemRevision.class, command.revisionId(), LockModeType.PESSIMISTIC_WRITE);
        if (revision == null) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_REVISION_NOT_FOUND);
        }
        existing = validations.findByRequestedByAndIdempotencyKey(command.authorId(), command.key());
        if (existing.isPresent()) {
            var result = response(existing.get(), command.hash());
            markUnreferenced(assetId);
            return result;
        }
        validateTarget(problem, revision, command.authorId(), command.language());
        var asset = codes.findByIdForUpdate(assetId).orElseThrow();
        if (!asset.getSha256().equals(command.code().sha256()) || asset.getSizeBytes() != command.code().sizeBytes()) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        try {
            asset.linkToProblem(command.problemId(), command.authorId());
        } catch (IllegalStateException failure) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        var validation
            = validations
                .saveAndFlush(
                    ProblemValidation
                        .request(
                            command.revisionId(),
                            command.authorId(),
                            command.language(),
                            assetId,
                            command.key(),
                            command.hash()
                        )
                );
        var response = ProblemValidationResponse.from(validation);
        events
            .append(
                "PROBLEM_VALIDATION",
                "ProblemValidationRequested",
                "v1",
                response.validationId(),
                command.correlationId(),
                new Requested(
                    response.validationId(),
                    response.problemRevisionId(),
                    response.language(),
                    command.hash()
                )
            );
        jdbc
            .update(
                """
                    INSERT INTO audit_logs(actor_id,action,target_type,target_id,before_state,after_state,
                    reason,client_ip,created_at) VALUES (?,'PROBLEM_VALIDATION_REQUEST','PROBLEM',?,NULL,?,
                    '검증 코드 접수',?,?)
                    """,
                command.authorId(),
                Long.toString(command.problemId()),
                mapper.writeValueAsString(response),
                command.clientIp(),
                LocalDateTime.now(clock)
            );
        return response;
    }

    /**
     * 참조가 없는 완료 파일에만 연결 실패를 기록한다.
     *
     * @param assetId 이번 요청에서 업로드한 파일
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLinkFailure(long assetId) {
        markUnreferenced(assetId);
    }

    /**
     * 파일 잠금 아래 실제 참조·상태를 다시 확인한다.
     *
     * @param assetId 코드 파일
     */
    private void markUnreferenced(long assetId) {
        codes.findByIdForUpdate(assetId).orElseThrow();
        jdbc.update("""
            UPDATE problem_code_assets a SET failure_code='VALIDATION_NOT_COMMITTED', updated_at=?
            WHERE id=? AND status='PENDING' AND uploaded_at IS NOT NULL AND upload_finished_at IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM problem_validations v WHERE v.code_asset_id=a.id)
            """, LocalDateTime.now(clock), assetId);
    }

    /**
     * 소속·권한과 보관·기존 검증 상태를 확인한다.
     *
     * @param problem 문제
     * @param revision 회차
     * @param authorId 관리자
     * @param language 언어
     * @throws ProblemException 회차 소속 불일치는 {@code PROBLEM_REVISION_NOT_FOUND}, 권한 부족은 {@code AUTH_ACCESS_DENIED},
     *     진행 중은 {@code PROBLEM_VALIDATION_IN_PROGRESS}, 보관 또는 통과 상태는 {@code PROBLEM_INVALID_STATE_TRANSITION}
     */
    private void validateTarget(Problem problem, ProblemRevision revision, long authorId, String language) {
        if (!revision.getProblemId().equals(problem.getId())) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_REVISION_NOT_FOUND);
        }
        if (!problem.getAuthorId().equals(authorId)) {
            throw new ProblemException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        if (problem.getVisibility() == ProblemVisibility.ARCHIVED) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_INVALID_STATE_TRANSITION);
        }
        var history = validations.findByProblemRevisionIdAndLanguage(revision.getId(), language);
        if (history.stream().anyMatch(ProblemValidation::hasPassed)) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_INVALID_STATE_TRANSITION);
        }
        if (history.stream().anyMatch(ProblemValidation::isPending)) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_VALIDATION_IN_PROGRESS);
        }
    }

    /**
     * 동일 해시만 최초 응답으로 변환한다.
     *
     * @param value 기존 요청
     * @param hash 이번 요청 해시
     * @return 최초 접수 응답
     * @throws ProblemException 동일 키의 다른 입력이면 {@code IDEMPOTENCY_KEY_CONFLICT}
     */
    private ProblemValidationResponse response(ProblemValidation value, String hash) {
        if (!value.getRequestHash().equals(hash)) {
            throw new ProblemException(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT);
        }
        return ProblemValidationResponse.from(value);
    }

    /** 최종 트랜잭션에 넘기는 고정 접수 정보. */
    public record Command(
        long problemId,
        long revisionId,
        long authorId,
        String language,
        String key,
        String hash,
        ProblemAssetContent code,
        String correlationId,
        String clientIp
    ) {
    }
    /** 원문과 S3 주소 없이 발행하는 검증 요청 payload. */
    private record Requested(String validationId, String problemRevisionId, String language, String requestHash) {
    }
}
