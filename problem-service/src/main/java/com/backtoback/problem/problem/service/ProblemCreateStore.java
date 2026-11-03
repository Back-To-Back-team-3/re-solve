package com.backtoback.problem.problem.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.domain.ProblemRevisionTestEntry;
import com.backtoback.problem.problem.dto.response.ProblemCreateResponse;
import com.backtoback.problem.problem.repository.ProblemRevisionRepository;
import com.backtoback.problem.problem.repository.ProblemRevisionTestEntryRepository;
import com.backtoback.problem.problem.repository.ProblemTestAssetRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** 최초 회차·파일 연결·멱등 응답·감사를 한 트랜잭션으로 확정한다. */
@Service
@RequiredArgsConstructor
public class ProblemCreateStore {
    private final ProblemRegistrationService registration;
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
        var response = ProblemCreateResponse.from(result);
        jdbc
            .update(
                """
                    INSERT INTO audit_logs(actor_id,action,target_type,target_id,before_state,after_state,
                    reason,client_ip,created_at)
                    VALUES (?,'PROBLEM_CREATE','PROBLEM',?,NULL,?,'문제 최초 등록',?,?)
                    """,
                command.authorId(),
                result.problemId().toString(),
                mapper.writeValueAsString(response),
                clientIp,
                LocalDateTime.now(clock)
            );
        return response;
    }
}
