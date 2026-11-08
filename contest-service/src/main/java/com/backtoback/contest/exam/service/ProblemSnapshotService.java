package com.backtoback.contest.exam.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.repository.ContestResourceGuardRepository;
import com.backtoback.contest.exam.repository.ContestResourceGuardRepository.Resource;

import lombok.RequiredArgsConstructor;

/**
 * 문제의 최신 원천 상태를 가드 잠금 아래 저장하고 동일 작업의 완료를 복구한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>이벤트 계약서 v1.0 / §1.5 순서 값 규칙·§4.2 problem의 P1 {@code ProblemStateChanged} v2: 버전 비교와 작업 복구</li>
 * <li>도메인 및 데이터베이스 v1.0 / §4.4.16 자원 작업 가드: 상태와 원천 스냅샷 저장</li>
 * <li>정책 및 상태 v1.0 / §4 문제·테스트케이스 정책: 문제 용도와 공개 상태</li>
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ProblemSnapshotService {
    private final ContestResourceGuardRepository guards;
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /** 문제 상태 이벤트의 검증된 원천 스냅샷이다. */
    public record Snapshot(
        long problemId,
        long sourceVersion,
        String scope,
        String visibility,
        Long revisionId,
        Integer revisionNumber,
        List<String> languages,
        String operationId
    ) {
        /** 회차와 언어는 같은 원천 버전의 완전한 스냅샷이어야 한다. */
        public Snapshot {
            if (problemId <= 0 || sourceVersion <= 0 || !Set.of("GENERAL", "EXAM_ONLY").contains(scope)
                || !Set.of("PRIVATE", "PUBLIC", "ARCHIVED").contains(visibility)
                || (revisionId == null) != (revisionNumber == null)
                || (revisionId != null && (revisionId <= 0 || revisionNumber <= 0)) || languages == null
                || (revisionId == null && !languages.isEmpty()) || (revisionId != null && languages.isEmpty())
                || ("PUBLIC".equals(visibility) && revisionId == null)
                || !Set.of("java", "python3", "cpp").containsAll(languages)
                || languages.size() != Set.copyOf(languages).size()
                || (operationId != null && (operationId.isBlank() || operationId.length() > 100))) {
                throw new IllegalArgumentException("Invalid problem snapshot");
            }
            languages = List.copyOf(languages);
        }
    }

    /**
     * 더 큰 원천 버전만 저장하고 같은 작업의 비공개·보관 이벤트로 준비를 종결한다.
     *
     * @param snapshot 검증된 문제 상태
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void apply(Snapshot snapshot) {
        guards.lockAll(List.of(new Resource("PROBLEM", snapshot.problemId())));
        var row
            = jdbc
                .queryForMap(
                    "SELECT * FROM contest_resource_guards WHERE resource_type = 'PROBLEM' "
                        + "AND resource_id = ? FOR UPDATE",
                    snapshot.problemId()
                );
        long storedVersion = ((Number)row.get("source_version")).longValue();
        String storedStatus = (String)row.get("source_status");
        String storedScope = (String)row.get("source_scope");
        String state = (String)row.get("state");
        String operationResult = (String)row.get("operation_result");
        String operationId = (String)row.get("operation_id");
        Number expected = (Number)row.get("expected_source_version");
        boolean newer = snapshot.sourceVersion() > storedVersion;
        if (newer && "ARCHIVED".equals(storedStatus) && !"ARCHIVED".equals(snapshot.visibility())) {
            throw new IllegalStateException("Archived problem cannot be restored");
        }
        if (newer && storedScope != null && !storedScope.equals(snapshot.scope())) {
            throw new IllegalStateException("Problem scope cannot change");
        }
        String latestStatus = newer ? snapshot.visibility() : storedStatus;
        String latestScope = newer ? snapshot.scope() : storedScope;
        boolean complete
            = "PREPARED".equals(state) && "PENDING".equals(operationResult)
                && "PROBLEM_HIDE".equals(row.get("operation_type")) && snapshot.operationId() != null
                && snapshot.operationId().equals(operationId) && expected != null
                && expected.longValue() < Long.MAX_VALUE && snapshot.sourceVersion() == expected.longValue() + 1
                && ("PRIVATE".equals(snapshot.visibility()) || "ARCHIVED".equals(snapshot.visibility()));
        if (complete) {
            state
                = "GENERAL".equals(latestScope) && "PUBLIC".equals(latestStatus)
                    && Math.max(storedVersion, snapshot.sourceVersion()) > snapshot.sourceVersion() ? "OPEN"
                        : "BLOCKED";
            operationResult = "COMMITTED";
        } else if (newer && !"PREPARED".equals(state)) {
            if ("ARCHIVED".equals(snapshot.visibility())
                || ("GENERAL".equals(snapshot.scope()) && "PRIVATE".equals(snapshot.visibility()))) {
                state = "BLOCKED";
            } else if ("BLOCKED".equals(state)
                && (operationResult == null || "COMMITTED".equals(operationResult) || "ABORTED".equals(operationResult))
                && "GENERAL".equals(snapshot.scope()) && "PUBLIC".equals(snapshot.visibility())
                && !"ARCHIVED".equals(storedStatus)) {
                state = "OPEN";
            }
        }
        if (newer) {
            String languages
                = snapshot
                    .languages()
                    .stream()
                    .sorted()
                    .map(language -> "\"" + language + "\"")
                    .collect(java.util.stream.Collectors.joining(",", "[", "]"));
            jdbc
                .update(
                    "UPDATE contest_resource_guards SET source_version = ?, source_status = ?, source_scope = ?, "
                        + "source_revision_id = ?, source_revision_number = ?, source_supported_languages = ?, "
                        + "state = ?, operation_result = ?, version = version + 1, updated_at = ? "
                        + "WHERE resource_type = 'PROBLEM' AND resource_id = ?",
                    snapshot.sourceVersion(),
                    snapshot.visibility(),
                    snapshot.scope(),
                    snapshot.revisionId(),
                    snapshot.revisionNumber(),
                    languages,
                    state,
                    operationResult,
                    LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE),
                    snapshot.problemId()
                );
        } else if (complete) {
            jdbc
                .update(
                    "UPDATE contest_resource_guards SET state = ?, operation_result = ?, "
                        + "version = version + 1, updated_at = ? "
                        + "WHERE resource_type = 'PROBLEM' AND resource_id = ?",
                    state,
                    operationResult,
                    LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE),
                    snapshot.problemId()
                );
        }
    }
}
