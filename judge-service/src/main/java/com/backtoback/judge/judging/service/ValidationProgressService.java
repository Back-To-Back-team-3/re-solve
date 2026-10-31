package com.backtoback.judge.judging.service;

import java.util.ArrayList;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.Assert;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.dto.ValidationProgressResponse;

import lombok.RequiredArgsConstructor;

/** 하나의 조회 스냅샷에서 작업과 최종 시도를 조합한다. (API §3.3.6) */
@Service
@RequiredArgsConstructor
public class ValidationProgressService {
    private final JdbcTemplate jdbc;

    /**
     * 잠금이나 원격 호출 없이 검증 진행을 조회한다.
     *
     * @param id 양의 검증 ID
     * @return 원문이 없는 진행 요약
     * @throws JudgeException {@code PROBLEM_VALIDATION_NOT_FOUND}: 작업이 없는 경우;
     *     {@code COMMON_INVALID_REQUEST}: ID가 양수가 아닌 경우
     * @throws IllegalStateException 저장된 종결 결과가 유효하지 않은 경우
     */
    @Transactional(readOnly = true)
    public ValidationProgressResponse find(long id) {
        if (id <= 0) {
            throw new JudgeException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        return jdbc.query("""
            SELECT j.validation_id,j.problem_revision_id,j.language,j.status,j.last_error,j.try_count,
            r.max_time_ms,r.runner_version,e.snapshot_hash,
            t.test_order,t.created_try_count,t.verdict,t.time_ms,t.memory_kb
            FROM judge_validation_jobs j
            LEFT JOIN judge_validation_runs r ON r.validation_id=j.validation_id AND r.try_count=j.try_count
            LEFT JOIN execution_runtimes e ON e.id=j.runtime_id
            LEFT JOIN judge_validation_test_results t ON t.validation_id=j.validation_id
            WHERE j.validation_id=? ORDER BY t.test_order
            """, row -> {
            if (!row.next()) {
                throw new JudgeException(JudgeErrorCode.PROBLEM_VALIDATION_NOT_FOUND);
            }
            String validationId = row.getString("validation_id");
            String revisionId = row.getString("problem_revision_id");
            String language = row.getString("language");
            String status = row.getString("status");
            boolean terminal = status.equals("PASSED") || status.equals("REJECTED") || status.equals("ERROR");
            String key = status.equals("PASSED") ? row.getString("snapshot_hash") : null;
            String runner = status.equals("PASSED") ? row.getString("runner_version") : null;
            Integer maximum = terminal ? (Integer)row.getObject("max_time_ms") : null;
            String message = status.equals("ERROR") || status.equals("REJECTED") ? row.getString("last_error") : null;
            if (status.equals("PASSED")) {
                Assert.state(key != null && runner != null && !runner.isBlank(), "통과 실행 환경이 없습니다.");
            }
            var tests = new ArrayList<ValidationProgressResponse.TestResult>();
            do {
                Integer order = (Integer)row.getObject("test_order");
                if (order != null) {
                    boolean current
                        = !status.equals("QUEUED") && !status.equals("RETRY_WAITING")
                            && Integer.valueOf(row.getInt("try_count")).equals(row.getObject("created_try_count"));
                    tests
                        .add(
                            new ValidationProgressResponse.TestResult(
                                order,
                                current ? row.getString("verdict") : null,
                                current ? (Integer)row.getObject("time_ms") : null,
                                current ? (Integer)row.getObject("memory_kb") : null
                            )
                        );
                }
            } while (row.next());
            return ValidationProgressResponse
                .from(validationId, revisionId, language, status, tests, maximum, message, key, runner);
        }, id);
    }
}
