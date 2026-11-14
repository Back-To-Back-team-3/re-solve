package com.backtoback.problem.problem.service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.domain.Problem;
import com.backtoback.problem.problem.domain.ProblemAssetStatus;
import com.backtoback.problem.problem.domain.ProblemRevision;
import com.backtoback.problem.problem.repository.ProblemRepository;
import com.backtoback.problem.problem.repository.ProblemRevisionRepository;
import com.backtoback.problem.problem.repository.ProblemRevisionTestEntryRepository;
import com.backtoback.problem.problem.repository.ProblemTestAssetRepository;
import com.backtoback.problem.problem.repository.ProblemValidationRepository;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;

/** 채점 문맥이 지정한 불변 회차와 통과 실행 환경을 조회한다. */
@Service
@RequiredArgsConstructor
public class ProblemJudgingStore {
    private final ProblemRepository problems;
    private final ProblemRevisionRepository revisions;
    private final ProblemValidationRepository validations;
    private final ProblemRevisionTestEntryRepository entries;
    private final ProblemTestAssetRepository assets;
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    /**
     * 최신 회차나 현재 공개 상태로 치환하지 않고 지정 자료를 반환한다(API 명세서 §2.6.1·§2.6.2).
     *
     * @param problemId 문제
     * @param revisionId 고정 회차
     * @return 채점 스냅샷
     * @throws ProblemException 식별자가 양수가 아니면 {@code COMMON_INVALID_REQUEST}, 문제가 없으면
     *     {@code PROBLEM_NOT_FOUND}, 회차가 없거나 소속이 다르면 {@code PROBLEM_REVISION_NOT_FOUND},
     *     고정 자료 조회·해석에 실패하면 {@code COMMON_DEPENDENCY_UNAVAILABLE}
     */
    @Transactional(readOnly = true)
    public Snapshot read(long problemId, long revisionId) {
        if (problemId <= 0 || revisionId <= 0) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        var problem
            = problems.findById(problemId).orElseThrow(() -> new ProblemException(ProblemErrorCode.PROBLEM_NOT_FOUND));
        var revision
            = revisions
                .findById(revisionId)
                .filter(value -> value.getProblemId() == problemId)
                .orElseThrow(() -> new ProblemException(ProblemErrorCode.PROBLEM_REVISION_NOT_FOUND));
        try {
            Map<String, Object> function
                = mapper
                    .readValue(
                        revision.getFunctionSpecJson(),
                        mapper.getTypeFactory().constructMapType(Map.class, String.class, Object.class)
                    );
            if (function == null || function.get("name") == null || revision.getTimeLimitMs() < 1
                || revision.getMemoryLimitKb() < 1 || revision.getWallTimeLimitMs() < 1
                || revision.getOutputLimitKb() < 1) {
                throw new IllegalStateException("고정 실행 조건이 손상되었습니다.");
            }
            var cases = entries.findByProblemRevisionIdOrderByTestOrder(revisionId).stream().map(entry -> {
                var asset = assets.findById(entry.getTestAssetId()).orElseThrow();
                if (asset.getStatus() != ProblemAssetStatus.LINKED || asset.getSha256() == null) {
                    throw new IllegalStateException("테스트 파일이 준비되지 않았습니다.");
                }
                return new Test(
                    asset.getId(),
                    entry.getTestOrder(),
                    entry.isSample(),
                    asset.getObjectKey(),
                    asset.getObjectVersion(),
                    asset.getSha256()
                );
            }).toList();
            if (cases.isEmpty()) {
                throw new IllegalStateException("테스트가 없습니다.");
            }
            Map<String, Profile> profiles = new LinkedHashMap<>();
            validations
                .findByProblemRevisionIdOrderByIdDesc(revisionId)
                .stream()
                .filter(value -> "PASSED".equals(value.getResultStatus()))
                .forEach(value -> {
                    if (value.getRuntimeKey() == null || value.getRuntimeKey().isBlank()
                        || value.getRunnerVersion() == null || value.getRunnerVersion().isBlank()) {
                        throw new IllegalStateException("통과 실행 환경이 없습니다.");
                    }
                    profiles
                        .putIfAbsent(
                            value.getLanguage(),
                            new Profile(value.getLanguage(), value.getRuntimeKey(), value.getRunnerVersion())
                        );
                });
            var tags = jdbc.query("""
                SELECT t.id,t.name FROM problem_revision_tags rt JOIN tags t ON t.id=rt.tag_id
                WHERE rt.problem_revision_id=? ORDER BY t.id
                """, (row, index) -> new ProblemReadStore.Tag(row.getLong("id"), row.getString("name")), revisionId);
            return new Snapshot(
                problem,
                revision,
                function,
                tags,
                cases,
                profiles.values().stream().sorted(java.util.Comparator.comparing(Profile::language)).toList()
            );
        } catch (RuntimeException invalid) {
            throw new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, invalid);
        }
    }

    /** 고정 테스트 파일 참조. */
    public record Test(long id, int order, boolean sample, String key, String version, String sha256) {
    }
    /** 검증 당시 원본 실행 환경. */
    public record Profile(String language, String runtimeKey, String runnerVersion) {
    }
    /** 같은 조회 시점의 문제·불변 회차·태그·테스트·프로필. */
    public record Snapshot(
        Problem problem,
        ProblemRevision revision,
        Map<String, Object> function,
        List<ProblemReadStore.Tag> tags,
        List<Test> tests,
        List<Profile> profiles
    ) {
    }
}
