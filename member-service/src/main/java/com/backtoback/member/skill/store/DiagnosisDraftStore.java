package com.backtoback.member.skill.store;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * 진단 Draft(ERD 4.1.8)와 문항별 마지막 제출(4.1.9) 조회. Draft는 모의 코테와 같이 {@code seq}가 더 큰 요청만 반영한다(정책 5.3).
 */
@Repository
@RequiredArgsConstructor
public class DiagnosisDraftStore {

    private final JdbcTemplate jdbcTemplate;

    /**
     * 문항·언어별 Draft를 저장한다. 저장된 {@code seq}보다 작거나 같은 요청은 반영하지 않는다(늦게 도착한 이전 Draft).
     *
     * @return 반영 여부와 저장 후 서버의 {@code seq}·저장 시각
     */
    @Transactional
    public Draft save(long attemptId, long problemId, String language, String sourceCode, long seq, LocalDateTime now) {
        if (update(attemptId, problemId, language, sourceCode, seq, now) == 0) {
            int inserted
                = jdbcTemplate
                    .update(
                        "insert ignore into diagnosis_drafts (diagnosis_attempt_id, problem_id, language, source_code, "
                            + "seq, saved_at, version, created_at, updated_at) values (?, ?, ?, ?, ?, ?, 0, ?, ?)",
                        attemptId,
                        problemId,
                        language,
                        sourceCode,
                        seq,
                        now,
                        now,
                        now
                    );
            // 동시에 첫 저장이 들어와 다른 요청이 먼저 넣었으면, 그 행보다 큰 seq인지 한 번 더 확인한다.
            if (inserted == 0) {
                update(attemptId, problemId, language, sourceCode, seq, now);
            }
        }
        return jdbcTemplate
            .queryForObject(
                "select language, source_code, seq, saved_at from diagnosis_drafts "
                    + "where diagnosis_attempt_id = ? and problem_id = ? and language = ?",
                (row, index) -> new Draft(
                    row.getString("language"),
                    row.getString("source_code"),
                    row.getLong("seq"),
                    row.getObject("saved_at", LocalDateTime.class)
                ),
                attemptId,
                problemId,
                language
            );
    }

    /**
     * 문항별로 {@code seq}가 가장 큰 Draft (언어를 바꿔 가며 저장해도 마지막 작성 코드로 복구한다).
     */
    public Map<Long, Draft> findLatest(long attemptId) {
        Map<Long, Draft> latest = new HashMap<>();
        jdbcTemplate
            .query(
                "select problem_id, language, source_code, seq, saved_at from diagnosis_drafts "
                    + "where diagnosis_attempt_id = ? order by seq",
                row -> {
                    latest
                        .put(
                            row.getLong("problem_id"),
                            new Draft(
                                row.getString("language"),
                                row.getString("source_code"),
                                row.getLong("seq"),
                                row.getObject("saved_at", LocalDateTime.class)
                            )
                        );
                },
                attemptId
            );
        return latest;
    }

    /**
     * 문항별 마지막 제출의 상태와 제출한 Draft 순번.
     */
    public Map<Long, LastSubmission> findLastSubmissions(long attemptId) {
        Map<Long, LastSubmission> last = new HashMap<>();
        List<Map<String, Object>> rows
            = jdbcTemplate
                .queryForList(
                    "select problem_id, status, draft_seq from diagnosis_submissions "
                        + "where diagnosis_attempt_id = ? order by id",
                    attemptId
                );
        rows
            .forEach(
                row -> last
                    .put(
                        ((Number)row.get("problem_id")).longValue(),
                        new LastSubmission((String)row.get("status"), ((Number)row.get("draft_seq")).longValue())
                    )
            );
        return last;
    }

    private int update(
        long attemptId,
        long problemId,
        String language,
        String sourceCode,
        long seq,
        LocalDateTime now
    ) {
        return jdbcTemplate
            .update(
                "update diagnosis_drafts set source_code = ?, seq = ?, saved_at = ?, version = version + 1, "
                    + "updated_at = ? "
                    + "where diagnosis_attempt_id = ? and problem_id = ? and language = ? and seq < ?",
                sourceCode,
                seq,
                now,
                now,
                attemptId,
                problemId,
                language,
                seq
            );
    }

    public record Draft(String language, String sourceCode, long seq, LocalDateTime savedAt) {
    }

    public record LastSubmission(String status, long draftSeq) {
    }
}
