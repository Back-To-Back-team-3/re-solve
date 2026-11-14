package com.backtoback.problem.problem.repository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import com.backtoback.problem.problem.dto.request.ProblemSearchRequest;

import lombok.RequiredArgsConstructor;

/** 통과 사용 회차를 선택한 뒤 회원 풀이 상태와 검색 조건을 적용한다. */
@Repository
@RequiredArgsConstructor
public class ProblemSearchRepository {
    private static final String PUBLIC_CURRENT_REVISION = """
         FROM problems p JOIN problem_revisions r ON r.problem_id=p.id
         WHERE p.scope='GENERAL' AND p.visibility='PUBLIC'
         AND r.revision_number=(SELECT MAX(candidate.revision_number) FROM problem_revisions candidate
             WHERE candidate.problem_id=p.id AND EXISTS (SELECT 1 FROM problem_validations validation
                 WHERE validation.problem_revision_id=candidate.id AND validation.result_status='PASSED'))
        """;

    private final NamedParameterJdbcTemplate jdbc;

    /**
     * 제목·태그·난이도·본인 풀이 상태로 사용 회차를 검색한다.
     *
     * @param member 조회 회원
     * @param input 검색 조건
     * @return 사용 회차 ID 페이지
     */
    public Page<Long> search(long member, ProblemSearchRequest input) {
        var filter = input.normalized();
        var sql = new StringBuilder(PUBLIC_CURRENT_REVISION);
        Map<String, Object> params = new HashMap<>();
        if (filter.keyword() != null && !filter.keyword().isBlank()) {
            sql.append(" AND LOCATE(:keyword, r.title)>0");
            params.put("keyword", filter.keyword());
        }
        if (filter.difficulty() != null) {
            sql.append(" AND r.difficulty=:difficulty");
            params.put("difficulty", filter.difficulty());
        }
        if (filter.tagIds() != null && !filter.tagIds().isEmpty()) {
            sql
                .append(
                    " AND EXISTS (SELECT 1 FROM problem_revision_tags t"
                        + " WHERE t.problem_revision_id=r.id AND t.tag_id IN (:tags))"
                );
            params.put("tags", filter.tagIds());
        }
        if (filter.solvedStatus() != null) {
            sql.append("SOLVED".equals(filter.solvedStatus()) ? " AND EXISTS " : " AND NOT EXISTS ");
            sql
                .append(
                    "(SELECT 1 FROM member_problem_statuses m WHERE m.problem_id=p.id"
                        + " AND m.member_id=:member AND m.is_solved=true)"
                );
            params.put("member", member);
        }
        var parts = filter.sort().split(",");
        var column
            = Map.of("publishedAt", "r.created_at", "difficulty", "r.difficulty", "problemId", "p.id").get(parts[0]);
        long total = jdbc.queryForObject("SELECT COUNT(*)" + sql, params, Long.class);
        params.put("limit", filter.size());
        params.put("offset", (long)filter.page() * filter.size());
        var ids
            = jdbc
                .queryForList(
                    "SELECT r.id" + sql + " ORDER BY " + column + " " + parts[1]
                        + ",p.id ASC LIMIT :limit OFFSET :offset",
                    params,
                    Long.class
                );
        return new PageImpl<>(ids, PageRequest.of(filter.page(), filter.size()), total);
    }

    /**
     * 태그를 가진 현재 회차 중 난이도마다 한 문제를 무작위로 조회한다.
     *
     * @param tagId 현재 회차에 연결된 태그 ID
     * @return 난이도별 후보 목록
     */
    public List<DiagnosisCandidate> findDiagnosisCandidates(long tagId) {
        return jdbc
            .query(
                """
                    SELECT difficulty, problem_id, revision_id, title
                    FROM (
                        SELECT r.difficulty, p.id AS problem_id, r.id AS revision_id, r.title,
                            ROW_NUMBER() OVER (PARTITION BY r.difficulty ORDER BY RAND()) AS rank_in_difficulty
                    """ + PUBLIC_CURRENT_REVISION + """
                        AND r.difficulty BETWEEN 1 AND 3
                        AND EXISTS (SELECT 1 FROM problem_revision_tags t
                            WHERE t.problem_revision_id=r.id AND t.tag_id=:tagId)
                    ) ranked
                    WHERE rank_in_difficulty=1
                    ORDER BY difficulty
                    """,
                Map.of("tagId", tagId),
                (row, index) -> new DiagnosisCandidate(
                    row.getInt("difficulty"),
                    row.getLong("problem_id"),
                    row.getLong("revision_id"),
                    row.getString("title")
                )
            );
    }

    /** 진단에 출제 가능한 고정 회차. */
    public record DiagnosisCandidate(int difficulty, long problemId, long revisionId, String title) {
    }
}
