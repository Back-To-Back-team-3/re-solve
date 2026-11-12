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

    /**
     * 채점 결과를 반영할 진단 제출 (J5·J4 대조용).
     *
     * @param judgeAttempt 반영한 채점 회차. 채점 전이면 {@code null}
     */
    public Optional<ResultTarget> findResultTarget(long diagnosisSubmissionId) {
        List<ResultTarget> found
            = jdbcTemplate
                .query(
                    "select s.diagnosis_attempt_id, s.problem_id, s.judge_attempt, "
                        + "s.draft_seq = (select max(o.draft_seq) from diagnosis_submissions o "
                        + "where o.diagnosis_attempt_id = s.diagnosis_attempt_id "
                        + "and o.problem_id = s.problem_id) latest " + "from diagnosis_submissions s where s.id = ?",
                    (row, index) -> new ResultTarget(
                        row.getLong("diagnosis_attempt_id"),
                        row.getLong("problem_id"),
                        row.getObject("judge_attempt", Integer.class),
                        row.getBoolean("latest")
                    ),
                    diagnosisSubmissionId
                );
        return found.stream().findFirst();
    }

    /**
     * 채점 결과를 기록한다. {@code JUDGED}는 판정을, {@code FAILED}는 판정 없이 상태만 남긴다.
     */
    public void recordResult(
        long diagnosisSubmissionId,
        long submissionId,
        int judgeAttempt,
        String status,
        String verdict,
        LocalDateTime now
    ) {
        jdbcTemplate
            .update(
                "update diagnosis_submissions set status = ?, submission_id = ?, judge_attempt = ?, verdict = ?, "
                    + "updated_at = ? where id = ?",
                status,
                submissionId,
                judgeAttempt,
                verdict,
                now,
                diagnosisSubmissionId
            );
    }

    /**
     * 아직 채점이 끝나지 않은({@code ACCEPTED}·{@code REQUESTED}) 제출 수.
     */
    public long countPending(long attemptId) {
        return jdbcTemplate
            .queryForObject(
                "select count(*) from diagnosis_submissions "
                    + "where diagnosis_attempt_id = ? and status in ('ACCEPTED', 'REQUESTED')",
                Long.class,
                attemptId
            );
    }

    /**
     * 채점 장애({@code FAILED}) 제출이 있는지. 있으면 쿨타임을 면제한다(정책 및 상태 8.3).
     */
    public boolean anyFailed(long attemptId) {
        return jdbcTemplate
            .queryForObject(
                "select count(*) from diagnosis_submissions where diagnosis_attempt_id = ? and status = 'FAILED'",
                Long.class,
                attemptId
            ) > 0;
    }

    /**
     * 종료됐고 모든 제출이 종결된 응시 (채점 확정 보정, 스케줄러 1.2).
     */
    public List<Long> findScorableAttemptIds(int limit) {
        return jdbcTemplate
            .queryForList(
                "select a.id from diagnosis_attempts a where a.status = 'CLOSED' and not exists ("
                    + "select 1 from diagnosis_submissions s where s.diagnosis_attempt_id = a.id "
                    + "and s.status in ('ACCEPTED', 'REQUESTED')) order by a.closed_at, a.id limit ?",
                Long.class,
                limit
            );
    }

    /**
     * 쿨타임 해제 알림 대상 (스케줄러 1.3): 태그별 마지막 응시가 {@code SCORED}·쿨타임 대상이고 {@code closed_at <= closedBefore},
     * 현재 레벨 {@code LV1}·{@code LV2}, 알림 미발송. 해제 전에 같은 태그의 새 응시가 시작되면 빠진다.
     */
    public List<CooldownRelease> findCooldownReleases(LocalDateTime closedBefore, int limit) {
        return jdbcTemplate
            .query(
                "select a.id, a.member_id, a.tag_id, a.tag_name, l.level from diagnosis_attempts a "
                    + "join member_tag_levels l on l.member_id = a.member_id and l.tag_id = a.tag_id "
                    + "where a.status = 'SCORED' and a.is_cooldown_exempt = false and a.cooldown_notified_at is null "
                    + "and a.closed_at <= ? and l.level in ('LV1', 'LV2') and not exists ("
                    + "select 1 from diagnosis_attempts b where b.member_id = a.member_id and b.tag_id = a.tag_id "
                    + "and b.id > a.id) order by a.closed_at, a.id limit ?",
                (row, index) -> new CooldownRelease(
                    row.getLong("id"),
                    row.getLong("member_id"),
                    row.getLong("tag_id"),
                    row.getString("tag_name"),
                    row.getString("level")
                ),
                closedBefore,
                limit
            );
    }

    /**
     * 쿨타임 해제 알림을 한 번만 보내도록 표시한다.
     *
     * @return 이번에 표시했으면 {@code true} (다른 인스턴스·재실행이 먼저 표시했으면 {@code false})
     */
    public boolean markCooldownNotified(long attemptId, LocalDateTime now) {
        return jdbcTemplate
            .update(
                "update diagnosis_attempts set cooldown_notified_at = ?, updated_at = ? "
                    + "where id = ? and cooldown_notified_at is null",
                now,
                now,
                attemptId
            ) == 1;
    }

    public record Submission(long id, String status, LocalDateTime receivedAt) {
    }

    /**
     * @param level 현재 태그 레벨 (알림의 {@code toLevel})
     */
    public record CooldownRelease(long attemptId, long memberId, long tagId, String tagName, String level) {
    }

    /**
     * @param latest 문항에서 가장 큰 {@code draft_seq}의 제출인지 (문항 최종 판정)
     */
    public record ResultTarget(long attemptId, long problemId, Integer judgeAttempt, boolean latest) {
    }
}
