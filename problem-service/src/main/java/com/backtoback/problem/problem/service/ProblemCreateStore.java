package com.backtoback.problem.problem.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.domain.ProblemRevisionTestEntry;
import com.backtoback.problem.problem.domain.ProblemVisibility;
import com.backtoback.problem.problem.dto.response.ProblemCreateResponse;
import com.backtoback.problem.problem.repository.ProblemRepository;
import com.backtoback.problem.problem.repository.ProblemRevisionRepository;
import com.backtoback.problem.problem.repository.ProblemRevisionTestEntryRepository;
import com.backtoback.problem.problem.repository.ProblemTestAssetRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** 회차·파일 연결·멱등 응답·자동 검증·감사를 한 트랜잭션으로 확정한다. */
@Service
@RequiredArgsConstructor
public class ProblemCreateStore {
    private final ProblemRegistrationService registration;
    private final ProblemRepository problems;
    private final ProblemRevisionValidationService autoValidations;
    private final ProblemRevisionRepository revisions;
    private final ProblemTestAssetRepository assets;
    private final ProblemRevisionTestEntryRepository tests;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final Clock clock;

    /**
     * 별도 읽기 트랜잭션에서 같은 관리자·키의 최초 응답을 복원한다.
     *
     * @param authorId 작성 관리자
     * @param key 멱등키
     * @param hash 최초 등록 요청 해시
     * @return 동일 요청의 최초 응답, 없으면 빈 값
     * @throws ProblemException 같은 키의 본문이 다르면 IDEMPOTENCY_KEY_CONFLICT
     */
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        readOnly = true
    )
    public Optional<ProblemCreateResponse> replay(long authorId, String key, String hash) {
        return revisions.findByAuthorIdAndIdempotencyKey(authorId, key).map(revision -> {
            if (!hash.equals(revision.getRequestHash())) {
                throw new ProblemException(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            }
            return ProblemCreateResponse.from(revision);
        });
    }

    /**
     * 외부 파일 작업 전에 대상·작성자·상태·버전을 확인한다. 최종 저장에서도 문제 잠금 아래 재검사한다.
     *
     * @param problemId 대상 문제
     * @param authorId 관리자
     * @param version 기대 버전
     * @throws ProblemException 대상 없음은 {@code PROBLEM_NOT_FOUND}, 타인은 {@code AUTH_ACCESS_DENIED},
     *     보관 상태는 {@code PROBLEM_INVALID_STATE_TRANSITION}, 버전 불일치는 {@code PROBLEM_VERSION_CONFLICT}
     */
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        readOnly = true
    )
    public void validateRevision(long problemId, long authorId, int version) {
        var problem
            = problems.findById(problemId).orElseThrow(() -> new ProblemException(ProblemErrorCode.PROBLEM_NOT_FOUND));
        if (!problem.getAuthorId().equals(authorId)) {
            throw new ProblemException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        if (problem.getVisibility() == ProblemVisibility.ARCHIVED) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_INVALID_STATE_TRANSITION);
        }
        if (problem.getRowVersion() != version) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_VERSION_CONFLICT);
        }
    }

    /**
     * 최종 저장 실패 또는 멱등 경합 패자의 미연결 파일에 실패 분류만 남긴다.
     * 저장·쓰기 종료 시각과 파일 상태를 보존하며 S3 객체를 삭제하지 않는다.
     *
     * @param files 해당 요청에서 업로드 완료한 파일
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordLinkFailure(ProblemCreateFiles files) {
        markUnreferencedFiles(files);
    }

    /**
     * 현재 트랜잭션에서 참조와 상태를 재검사한 파일에만 실패 분류를 기록한다.
     *
     * @param files 해당 요청에서 업로드 완료한 파일
     */
    private void markUnreferencedFiles(ProblemCreateFiles files) {
        jdbc.queryForList("SELECT id FROM problem_body_assets WHERE id=? FOR UPDATE", Long.class, files.bodyId());
        jdbc.update("""
            UPDATE problem_body_assets a SET failure_code='REGISTRATION_NOT_COMMITTED'
            WHERE a.id=? AND a.status='PENDING' AND a.uploaded_at IS NOT NULL AND a.upload_finished_at IS NOT NULL
            AND NOT EXISTS (SELECT 1 FROM problem_revisions r WHERE r.body_asset_id=a.id)
            AND NOT EXISTS (SELECT 1 FROM problem_body_images i WHERE i.body_asset_id=a.id)
            """, files.bodyId());
        for (Long id : files.tests().stream().map(ProblemCreateFiles.TestFile::id).distinct().sorted().toList()) {
            jdbc.queryForList("SELECT id FROM problem_test_assets WHERE id=? FOR UPDATE", Long.class, id);
            jdbc.update("""
                UPDATE problem_test_assets a SET failure_code='REGISTRATION_NOT_COMMITTED'
                WHERE a.id=? AND a.status='PENDING' AND a.uploaded_at IS NOT NULL AND a.upload_finished_at IS NOT NULL
                AND NOT EXISTS (SELECT 1 FROM problem_revision_tests t WHERE t.test_asset_id=a.id)
                """, id);
        }
    }

    /**
     * 업로드 완료 파일을 순서대로 잠그고 테스트 연결과 감사까지 원자 저장한다.
     *
     * @param command 최초 회차 입력
     * @param files 해당 요청에서 업로드한 파일
     * @param clientIp 요청의 네트워크 주소
     * @return 최초 등록 응답
     * @throws ProblemException 파일 내용·소속·연결 상태가 달라진 경우 COMMON_INVALID_REQUEST
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ProblemCreateResponse save(ProblemRegistrationCommand command, ProblemCreateFiles files, String clientIp) {
        return save(command, files, clientIp, null);
    }

    /**
     * 회차·파일·자동 검증·감사를 원자 저장한다. 잠금 대기 후 최신 통과 코드를 보도록 READ_COMMITTED를 사용한다.
     *
     * @param command 저장 입력
     * @param files 원문 대조와 업로드를 마친 파일
     * @param clientIp 감사 주소
     * @param correlationId 자동 검증 이벤트 추적 ID
     * @return 최초 저장 또는 멱등 응답
     * @throws ProblemException 입력·참조·멱등·버전 조건을 위반한 경우
     */
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.READ_COMMITTED
    )
    public ProblemCreateResponse save(
        ProblemRegistrationCommand command,
        ProblemCreateFiles files,
        String clientIp,
        String correlationId
    ) {
        var result = registration.save(command, files.tests().stream().map(ProblemCreateFiles.TestFile::id).toList());
        if (result.replayed()) {
            markUnreferencedFiles(files);
            return ProblemCreateResponse.from(result);
        }
        for (int index = 0; index < files.tests().size(); index++) {
            var file = files.tests().get(index);
            var asset = assets.findByIdForUpdate(file.id()).orElseThrow();
            if (!file.sha256().equals(asset.getSha256()) || file.sizeBytes() != asset.getSizeBytes()) {
                throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
            }
            try {
                asset.linkToProblem(result.problemId(), command.authorId());
            } catch (IllegalStateException failure) {
                throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
            }
            tests
                .save(
                    ProblemRevisionTestEntry
                        .create(
                            result.problemRevisionId(),
                            file.id(),
                            index + 1,
                            command.testCases().get(index).isSample(),
                            LocalDateTime.now(clock)
                        )
                );
        }
        if (command.problemId() != null) {
            autoValidations.request(result.problemId(), result.problemRevisionId(), command.authorId(), correlationId);
        }
        var response = ProblemCreateResponse.from(result);
        jdbc
            .update(
                """
                    INSERT INTO audit_logs(actor_id,action,target_type,target_id,before_state,after_state,
                    reason,client_ip,created_at)
                    VALUES (?,?,'PROBLEM',?,NULL,?,?,?,?)
                    """,
                command.authorId(),
                command.problemId() == null ? "PROBLEM_CREATE" : "PROBLEM_REVISION_CREATE",
                result.problemId().toString(),
                mapper.writeValueAsString(response),
                command.problemId() == null ? "문제 최초 등록" : "새 문제 회차 등록",
                clientIp,
                LocalDateTime.now(clock)
            );
        return response;
    }
}
