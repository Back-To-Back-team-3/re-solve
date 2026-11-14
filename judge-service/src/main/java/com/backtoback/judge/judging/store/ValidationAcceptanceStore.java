package com.backtoback.judge.judging.store;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.judge.judging.dto.ProblemValidationRequest;

import lombok.RequiredArgsConstructor;

/** 공통 JDBC 소비 기록과 같은 트랜잭션에서 검증 작업을 멱등 저장한다. (정책 §3.5) */
@Repository
@RequiredArgsConstructor
public class ValidationAcceptanceStore {
    private final JdbcTemplate jdbc;
    private final Clock clock;

    /**
     * 최초 요청만 저장하고 같은 ID의 불변 필드를 현재 읽기로 대조한다.
     * 무변경 UPSERT로 동시 INSERT를 직렬화하며 기존 상태·시각·기한은 갱신하지 않는다.
     *
     * @param request 검증 요청 식별 정보
     * @param correlationId 최초 이벤트 추적 ID
     * @throws IllegalArgumentException 같은 검증 ID의 회차·언어·해시가 다른 경우
     * @throws org.springframework.dao.DataAccessException 저장 또는 잠금 조회에 실패한 경우
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void accept(ProblemValidationRequest request, String correlationId) {
        var now
            = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);
        jdbc
            .update(
                """
                    INSERT INTO judge_validation_jobs
                        (validation_id, problem_revision_id, language, request_hash, correlation_id,
                         status, try_count, next_run_at, deadline_at, created_at, updated_at)
                    VALUES (?, ?, ?, ?, ?, 'QUEUED', 0, ?, ?, ?, ?)
                    ON DUPLICATE KEY UPDATE validation_id = validation_id
                    """,
                request.validationId(),
                request.problemRevisionId(),
                request.language(),
                request.requestHash(),
                correlationId,
                now,
                now.plusMinutes(10),
                now,
                now
            );
        // REPEATABLE READ에서 상위 트랜잭션의 이전 스냅샷 대신 경합 승자의 최신 행을 읽는다.
        var stored
            = jdbc
                .queryForObject(
                    """
                        SELECT validation_id, problem_revision_id, language, request_hash
                        FROM judge_validation_jobs WHERE validation_id = ? FOR UPDATE
                        """,
                    (row, rowNumber) -> new ProblemValidationRequest(
                        row.getLong("validation_id"),
                        row.getLong("problem_revision_id"),
                        row.getString("language"),
                        row.getString("request_hash")
                    ),
                    request.validationId()
                );
        if (!request.equals(stored)) {
            throw new IllegalArgumentException("같은 검증 ID의 요청 내용이 일치하지 않습니다.");
        }
    }
}
