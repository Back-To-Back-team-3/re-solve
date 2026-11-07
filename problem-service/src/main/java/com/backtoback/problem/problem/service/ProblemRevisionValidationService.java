package com.backtoback.problem.problem.service;

import java.util.List;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.problem.problem.domain.ProblemValidation;
import com.backtoback.problem.problem.repository.ProblemCodeAssetRepository;
import com.backtoback.problem.problem.repository.ProblemValidationRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** 새 회차 저장 트랜잭션에 언어별 자동 검증 요청과 Outbox를 함께 기록한다(정책 및 상태 §4). */
@Service
@RequiredArgsConstructor
public class ProblemRevisionValidationService {
    private final JdbcTemplate jdbc;
    private final ProblemCodeAssetRepository codes;
    private final ProblemValidationRepository validations;
    private final OutboxEventWriter events;
    private final ObjectMapper mapper;

    /**
     * 문제 잠금을 보유한 호출자가 직전 사용 회차의 언어별 최신 통과 코드를 재사용한다.
     * 새 회차 저장이 멱등 재생된 경우 호출하지 않는다. 원격 조회·코드 실행은 수행하지 않는다.
     *
     * @param problemId 잠근 문제
     * @param revisionId 새 회차
     * @param authorId 작성 관리자
     * @param correlationId 최초 요청의 추적 ID
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void request(long problemId, long revisionId, long authorId, String correlationId) {
        var sources = jdbc.query("""
            SELECT v.language, v.code_asset_id FROM problem_validations v
            WHERE v.problem_revision_id=(
                SELECT r.id FROM problem_revisions r
                WHERE r.problem_id=? AND EXISTS (
                    SELECT 1 FROM problem_validations p WHERE p.problem_revision_id=r.id AND p.result_status='PASSED')
                ORDER BY r.revision_number DESC LIMIT 1)
            AND v.result_status='PASSED'
            AND NOT EXISTS (SELECT 1 FROM problem_validations n
                WHERE n.problem_revision_id=v.problem_revision_id AND n.language=v.language
                AND n.result_status='PASSED' AND n.id>v.id)
            ORDER BY v.code_asset_id, v.language
            """, (row, index) -> new Source(row.getString("language"), row.getLong("code_asset_id")), problemId);
        for (var source : sources) {
            var code = codes.findByIdForUpdate(source.assetId()).orElseThrow();
            code.linkToProblem(problemId, authorId);
            String hash
                = ProblemAssetContent
                    .fromBytes(
                        mapper
                            .writeValueAsBytes(
                                List
                                    .of(
                                        "AUTO_VALIDATION",
                                        problemId,
                                        revisionId,
                                        source.language(),
                                        code.getId(),
                                        code.getSha256()
                                    )
                            )
                    )
                    .sha256();
            var validation
                = validations
                    .saveAndFlush(
                        ProblemValidation.automatic(revisionId, authorId, source.language(), code.getId(), hash)
                    );
            var payload
                = new Requested(validation.getId().toString(), Long.toString(revisionId), source.language(), hash);
            events
                .append(
                    "PROBLEM_VALIDATION",
                    "ProblemValidationRequested",
                    "v1",
                    payload.validationId(),
                    correlationId,
                    payload
                );
        }
    }

    /** 사용 회차의 언어별 최신 통과 코드 참조. */
    private record Source(String language, long assetId) {
    }

    /** 원문을 제외한 고정 자동 검증 요청. */
    private record Requested(String validationId, String problemRevisionId, String language, String requestHash) {
    }
}
