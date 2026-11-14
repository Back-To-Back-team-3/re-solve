package com.backtoback.member.skill.store;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

import com.backtoback.member.skill.service.PracticeResult;
import com.backtoback.member.skill.service.TagAchievement;

import lombok.RequiredArgsConstructor;

/**
 * 연습 채점 결과 복제본(ERD 4.1.10·4.1.11)과 성취도(4.1.4)를 SQL로 다룬다. 호출하는 쪽의 트랜잭션에서 실행한다.
 */
@Repository
@RequiredArgsConstructor
public class PracticeResultStore {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 복제본을 최신 회차로 교체한다. 저장된 회차보다 작거나 같은 {@code judgeAttempt}는 무시한다(정책 및 상태 8.3 순서 역전).
     *
     * @return 교체했으면 교체 전 (난이도, 태그). 새로 저장했으면 빈 태그. 무시했으면 비어 있다.
     */
    public Optional<Previous> replace(PracticeResult result, LocalDateTime now) {
        List<Previous> stored
            = jdbcTemplate
                .query(
                    "select id, difficulty, judge_attempt from practice_result_replicas "
                        + "where submission_id = ? for update",
                    (row, index) -> new Previous(
                        row.getLong("id"),
                        row.getInt("difficulty"),
                        row.getInt("judge_attempt"),
                        Set.of()
                    ),
                    result.submissionId()
                );
        if (stored.isEmpty()) {
            long id = insert(result, now);
            insertTags(id, result.tagIds(), now);
            return Optional.of(new Previous(id, result.difficulty(), 0, Set.of()));
        }
        Previous previous = stored.get(0);
        if (result.judgeAttempt() <= previous.judgeAttempt()) {
            return Optional.empty();
        }
        Set<Long> previousTags
            = new HashSet<>(
                jdbcTemplate
                    .queryForList(
                        "select tag_id from practice_result_replica_tags where practice_result_replica_id = ?",
                        Long.class,
                        previous.id()
                    )
            );
        jdbcTemplate
            .update(
                "update practice_result_replicas set member_id = ?, problem_id = ?, difficulty = ?, verdict = ?, "
                    + "judge_attempt = ?, submitted_at = ?, synced_at = ?, updated_at = ? where id = ?",
                result.memberId(),
                result.problemId(),
                result.difficulty(),
                result.verdict(),
                result.judgeAttempt(),
                result.submittedAt(),
                now,
                now,
                previous.id()
            );
        jdbcTemplate
            .update("delete from practice_result_replica_tags where practice_result_replica_id = ?", previous.id());
        insertTags(previous.id(), result.tagIds(), now);
        return Optional.of(new Previous(previous.id(), previous.difficulty(), previous.judgeAttempt(), previousTags));
    }

    /**
     * (회원, 태그, 난이도) 성취도를 복제본에서 처음부터 다시 계산해 저장한다. 문제 단위로 센다: 채점 완료 제출이 있는 문제마다 총점에
     * 난이도 점수를 한 번 더하고, AC를 받은 문제는 획득 점수에도 더한다. 남은 제출이 없으면 행을 지운다.
     */
    public void recalculate(long memberId, long tagId, int difficulty, LocalDateTime now) {
        Counts counts
            = jdbcTemplate
                .queryForObject(
                    "select count(distinct r.problem_id) attempted, "
                        + "count(distinct case when r.verdict = 'AC' then r.problem_id end) solved "
                        + "from practice_result_replicas r "
                        + "join practice_result_replica_tags t on t.practice_result_replica_id = r.id "
                        + "where r.member_id = ? and t.tag_id = ? and r.difficulty = ?",
                    (row, index) -> new Counts(row.getInt("attempted"), row.getInt("solved")),
                    memberId,
                    tagId,
                    difficulty
                );
        if (Objects.requireNonNull(counts).attempted() == 0) {
            jdbcTemplate
                .update(
                    "delete from member_tag_achievements where member_id = ? and tag_id = ? and difficulty = ?",
                    memberId,
                    tagId,
                    difficulty
                );
            return;
        }
        // shortcut: 힌트 사용 정답의 1/2점 미반영(모두 만점), 힌트 사용 복제본(S8 HintReady)을 받을 때 획득 점수에 반영한다.
        BigDecimal points = BigDecimal.valueOf(difficulty);
        BigDecimal earned = points.multiply(BigDecimal.valueOf(counts.solved()));
        BigDecimal total = points.multiply(BigDecimal.valueOf(counts.attempted()));
        BigDecimal rate = earned.multiply(BigDecimal.valueOf(100)).divide(total, 2, RoundingMode.HALF_UP);
        jdbcTemplate
            .update(
                "insert into member_tag_achievements (member_id, tag_id, difficulty, solved_count, earned_points, "
                    + "total_points, achievement_rate, recalculated_at, created_at, updated_at) "
                    + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) as new on duplicate key update "
                    + "solved_count = new.solved_count, earned_points = new.earned_points, "
                    + "total_points = new.total_points, achievement_rate = new.achievement_rate, "
                    + "recalculated_at = new.recalculated_at, updated_at = new.updated_at",
                memberId,
                tagId,
                difficulty,
                counts.solved(),
                earned,
                total,
                rate,
                now,
                now,
                now
            );
    }

    /**
     * 회원의 성취도 전체 (태그·난이도 순).
     */
    public List<TagAchievement> findAchievements(long memberId) {
        return jdbcTemplate
            .query(
                "select tag_id, difficulty, solved_count, achievement_rate from member_tag_achievements "
                    + "where member_id = ? order by tag_id, difficulty",
                (row, index) -> new TagAchievement(
                    row.getLong("tag_id"),
                    row.getInt("difficulty"),
                    row.getInt("solved_count"),
                    row.getBigDecimal("achievement_rate")
                ),
                memberId
            );
    }

    private long insert(PracticeResult result, LocalDateTime now) {
        GeneratedKeyHolder keyHolder = new GeneratedKeyHolder();
        jdbcTemplate.update(connection -> {
            PreparedStatement statement
                = connection
                    .prepareStatement(
                        "insert into practice_result_replicas (submission_id, member_id, problem_id, difficulty, "
                            + "verdict, judge_attempt, submitted_at, synced_at, created_at, updated_at) "
                            + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        Statement.RETURN_GENERATED_KEYS
                    );
            statement.setLong(1, result.submissionId());
            statement.setLong(2, result.memberId());
            statement.setLong(3, result.problemId());
            statement.setInt(4, result.difficulty());
            statement.setString(5, result.verdict());
            statement.setInt(6, result.judgeAttempt());
            statement.setObject(7, result.submittedAt());
            statement.setObject(8, now);
            statement.setObject(9, now);
            statement.setObject(10, now);
            return statement;
        }, keyHolder);
        return Objects.requireNonNull(keyHolder.getKey()).longValue();
    }

    private void insertTags(long replicaId, Set<Long> tagIds, LocalDateTime now) {
        for (Long tagId : tagIds) {
            jdbcTemplate
                .update(
                    "insert into practice_result_replica_tags (practice_result_replica_id, tag_id, created_at) "
                        + "values (?, ?, ?)",
                    replicaId,
                    tagId,
                    now
                );
        }
    }

    /**
     * 교체 전 복제본 상태. 재계산 대상에 교체 전 (난이도, 태그)도 넣기 위해 쓴다.
     */
    public record Previous(long id, int difficulty, int judgeAttempt, Set<Long> tagIds) {
    }

    private record Counts(int attempted, int solved) {
    }
}
