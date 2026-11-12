package com.backtoback.member.skill.store;

import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 진단 제출 접수 (ERD 4.1.9). 같은 응시의 제출은 호출하는 쪽이 응시 행을 잠근 상태에서 기록한다.
 */
@Repository
@RequiredArgsConstructor
public class DiagnosisSubmissionStore {

    public static final String MANUAL = "MANUAL";
    public static final String AUTO = "AUTO";
    public static final String ACCEPTED = "ACCEPTED";

    private final JdbcTemplate jdbcTemplate;

    /**
     * 같은 {@code (응시, 문항, seq)} 접수 (1.5.5 멱등).
     */
    public Optional<Submission> find(long attemptId, long problemId, long draftSeq) {
        List<Submission> found
            = jdbcTemplate
                .query(
                    "select id, status, received_at from diagnosis_submissions "
                        + "where diagnosis_attempt_id = ? and problem_id = ? and draft_seq = ?",
                    (row, index) -> new Submission(
                        row.getLong("id"),
                        row.getString("status"),
                        row.getObject("received_at", LocalDateTime.class)
                    ),
                    attemptId,
                    problemId,
                    draftSeq
                );
        return found.stream().findFirst();
    }

    /**
     * 문항의 마지막 접수 시각 (같은 문항 5초 간격 확인).
     */
    public Optional<LocalDateTime> lastReceivedAt(long attemptId, long problemId) {
        return Optional
            .ofNullable(
                jdbcTemplate
                    .queryForObject(
                        "select max(received_at) from diagnosis_submissions "
                            + "where diagnosis_attempt_id = ? and problem_id = ?",
                        LocalDateTime.class,
                        attemptId,
                        problemId
                    )
            );
    }

    /**
     * 문항에서 이미 제출한 가장 큰 Draft 순번. 제출이 없으면 -1이다.
     */
    public long maxSubmittedSeq(long attemptId, long problemId) {
        Long max
            = jdbcTemplate
                .queryForObject(
                    "select max(draft_seq) from diagnosis_submissions "
                        + "where diagnosis_attempt_id = ? and problem_id = ?",
                    Long.class,
                    attemptId,
                    problemId
                );
        return max == null ? -1 : max;
    }

    /**
     * 제출을 {@code ACCEPTED}로 접수한다. 같은 {@code seq}는 {@code uk_diagnosis_submissions_attempt_problem_seq}가 막는다.
     *
     * @return 진단 제출 ID (judge 멱등키)
     */
    public long insert(long attemptId, long problemId, long draftSeq, String submissionType, LocalDateTime receivedAt) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement
                = connection
                    .prepareStatement(
                        "insert into diagnosis_submissions (diagnosis_attempt_id, problem_id, draft_seq, "
                            + "submission_type, received_at, status, created_at, updated_at) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS
                    );
            statement.setLong(1, attemptId);
            statement.setLong(2, problemId);
            statement.setLong(3, draftSeq);
            statement.setString(4, submissionType);
            statement.setObject(5, receivedAt);
            statement.setString(6, ACCEPTED);
            statement.setObject(7, receivedAt);
            statement.setObject(8, receivedAt);
            return statement;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    /**
     * SQS 전송이 성공하면 {@code ACCEPTED → REQUESTED}. 먼저 도착한 채점 결과({@code JUDGED}·{@code FAILED})는 덮지 않는다.
     */
    public void markRequested(long submissionId, LocalDateTime now) {
        jdbcTemplate
            .update(
                "update diagnosis_submissions set status = 'REQUESTED', updated_at = ? "
                    + "where id = ? and status = 'ACCEPTED'",
                now,
                submissionId
            );
    }

    public record Submission(long id, String status, LocalDateTime receivedAt) {
    }
}
