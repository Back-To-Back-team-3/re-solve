package com.backtoback.problem.problem.service;

import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.domain.Problem;
import com.backtoback.problem.problem.domain.ProblemRevision;
import com.backtoback.problem.problem.domain.ProblemScope;
import com.backtoback.problem.problem.domain.ProblemVisibility;
import com.backtoback.problem.problem.dto.request.ProblemSearchRequest;
import com.backtoback.problem.problem.repository.ProblemRepository;
import com.backtoback.problem.problem.repository.ProblemRevisionRepository;
import com.backtoback.problem.problem.repository.ProblemSearchRepository;
import com.backtoback.problem.problem.repository.ProblemValidationRepository;

import lombok.RequiredArgsConstructor;

/** 문제 상태·사용 회차·언어·태그를 같은 읽기 트랜잭션에서 조합한다. */
@Service
@RequiredArgsConstructor
public class ProblemReadStore {
    private final ProblemRepository problems;
    private final ProblemRevisionRepository revisions;
    private final ProblemValidationRepository validations;
    private final ProblemSearchRepository search;
    private final JdbcTemplate jdbc;

    /**
     * 일반 공개 문제의 사용 회차를 검색한다.
     *
     * @param member 조회 회원
     * @param request 검색 조건
     * @return 사용 회차 페이지
     * @throws ProblemException 회원 식별자·필터·페이지·정렬이 잘못되면 {@code COMMON_INVALID_REQUEST}
     */
    @Transactional(readOnly = true)
    public Page<Item> search(long member, ProblemSearchRequest request) {
        if (member <= 0) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        return search.search(member, request).map(id -> {
            var revision = revisions.findById(id).orElseThrow();
            var problem = problems.findById(revision.getProblemId()).orElseThrow();
            boolean solved = Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS (SELECT 1 FROM member_problem_statuses
                    WHERE member_id=? AND problem_id=? AND is_solved=true)
                """, Boolean.class, member, problem.getId()));
            return item(problem.getId(), problem, revision, true, solved);
        });
    }

    /**
     * 요청 순서를 유지해 목적별 신규 편입 가능 여부를 조회한다.
     *
     * @param ids 최대 50개 문제 ID
     * @param purpose 편입 용도
     * @return 없는 문제를 포함한 항목 목록
     * @throws ProblemException 식별자가 잘못되거나 50개를 초과하거나 용도가 잘못되면 {@code COMMON_INVALID_REQUEST}
     */
    @Transactional(readOnly = true)
    public List<Item> summaries(List<Long> ids, String purpose) {
        if (ids == null || ids.isEmpty() || ids.size() > 50 || ids.stream().anyMatch(id -> id == null || id <= 0)
            || purpose == null || !List.of("ASSIGNMENT", "EXAM", "CONTEST").contains(purpose)) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        return ids.stream().map(id -> {
            var problem = problems.findById(id).orElse(null);
            if (problem == null) {
                return item(id, null, null, false, false);
            }
            var number = validations.currentNumber(id);
            var revision = number == null ? null : revisions.findByProblemIdAndRevisionNumber(id, number).orElseThrow();
            boolean available
                = revision != null && problem.getVisibility() != ProblemVisibility.ARCHIVED
                    && (problem.getScope() == ProblemScope.GENERAL
                        && problem.getVisibility() == ProblemVisibility.PUBLIC
                        || "CONTEST".equals(purpose) && problem.getScope() == ProblemScope.EXAM_ONLY);
            return item(id, problem, revision, available, false);
        }).toList();
    }

    /**
     * 같은 조회 시점의 회차 태그와 통과 언어를 조합한다.
     *
     * @param id 요청 ID
     * @param problem 문제 또는 null
     * @param revision 고정 회차 또는 null
     * @param available 편입 가능 여부
     * @param solved 조회 회원 해결 여부
     * @return 요약 항목
     */
    private Item item(long id, Problem problem, ProblemRevision revision, boolean available, boolean solved) {
        var tags = revision == null ? List.<Tag>of() : jdbc.query("""
            SELECT t.id,t.name FROM problem_revision_tags rt JOIN tags t ON t.id=rt.tag_id
            WHERE rt.problem_revision_id=? ORDER BY t.id
            """, (row, index) -> new Tag(row.getLong("id"), row.getString("name")), revision.getId());
        var languages = revision == null ? List.<String>of() : validations.languages(revision.getId());
        return new Item(id, problem, revision, available && !languages.isEmpty(), solved, tags, languages);
    }

    /** 조회 시점의 태그 ID·이름. */
    public record Tag(long id, String name) {
    }
    /** 문제별 상태와 고정 사용 회차. */
    public record Item(
        long id,
        Problem problem,
        ProblemRevision revision,
        boolean available,
        boolean solved,
        List<Tag> tags,
        List<String> languages
    ) {
    }
}
