package com.backtoback.member.skill.store;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import lombok.RequiredArgsConstructor;

/**
 * 진단 Draft(ERD 4.1.8)와 문항별 마지막 제출(4.1.9) 조회. Draft는 모의 코테와 같이 {@code seq}가 더 큰 요청만 반영한다(정책 5.3).
 */
@Repository
@RequiredArgsConstructor
public class DiagnosisDraftStore {

    private final JdbcTemplate jdbcTemplate;

    /**
     * Draft를 저장한다. 순번은 문항 단위라, 언어와 관계없이 그 문항에 저장된 가장 큰 {@code seq}보다 작거나 같은 요청은
     * 반영하지 않는다(늦게 도착한 이전 Draft·같은 순번 재전송). 호출하는 쪽이 응시 행을 잠가 같은 응시의 저장을 한 줄로 세운다.
     *
     * @return 실제 반영 여부와 그 문항의 최신 Draft
     */
    public SaveResult save(
        long attemptId,
        long problemId,
        String language,
        String sourceCode,
        long seq,
        LocalDateTime now
    ) {
        Draft latest = findLatest(attemptId).get(problemId);
        if (latest != null && seq <= latest.seq()) {
            return new SaveResult(false, latest);
        }
        jdbcTemplate
            .update(
                "insert into diagnosis_drafts (diagnosis_attempt_id, problem_id, language, source_code, seq, saved_at, "
                    + "version, created_at, updated_at) values (?, ?, ?, ?, ?, ?, 0, ?, ?) as new "
                    + "on duplicate key update source_code = new.source_code, seq = new.seq, saved_at = new.saved_at, "
                    + "version = diagnosis_drafts.version + 1, updated_at = new.updated_at",
                attemptId,
                problemId,
                language,
                sourceCode,
                seq,
                now,
                now,
                now
            );
        return new SaveResult(true, new Draft(language, sourceCode, seq, now));
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

    public record Draft(String language, String sourceCode, long seq, LocalDateTime savedAt) {
    }

    public record LastSubmission(String status, long draftSeq) {
    }

    public record SaveResult(boolean applied, Draft draft) {
    }
}
