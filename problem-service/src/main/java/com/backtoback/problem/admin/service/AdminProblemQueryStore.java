package com.backtoback.problem.admin.service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.admin.dto.AdminProblemSearchRequest;
import com.backtoback.problem.admin.repository.AdminProblemQueryRepository;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.domain.Problem;
import com.backtoback.problem.problem.domain.ProblemRevision;
import com.backtoback.problem.problem.domain.ProblemValidation;
import com.backtoback.problem.problem.repository.ProblemBodyAssetRepository;
import com.backtoback.problem.problem.repository.ProblemCodeAssetRepository;
import com.backtoback.problem.problem.repository.ProblemRepository;
import com.backtoback.problem.problem.repository.ProblemRevisionRepository;
import com.backtoback.problem.problem.repository.ProblemRevisionTagRepository;
import com.backtoback.problem.problem.repository.ProblemRevisionTestEntryRepository;
import com.backtoback.problem.problem.repository.ProblemTestAssetRepository;
import com.backtoback.problem.problem.repository.ProblemValidationRepository;
import com.backtoback.problem.problem.service.ProblemValidationDataStore.File;
import com.backtoback.problem.problem.service.ProblemValidationDataStore.TestFile;

import lombok.RequiredArgsConstructor;

/** 작성 권한을 검사하고 원격 호출 전에 같은 DB 스냅샷의 회차와 파일 참조를 고정한다. */
@Service
@RequiredArgsConstructor
public class AdminProblemQueryStore {
    private final AdminProblemQueryRepository query;
    private final ProblemRepository problems;
    private final ProblemRevisionRepository revisions;
    private final ProblemValidationRepository validations;
    private final ProblemBodyAssetRepository bodies;
    private final ProblemTestAssetRepository tests;
    private final ProblemCodeAssetRepository codes;
    private final ProblemRevisionTestEntryRepository entries;
    private final ProblemRevisionTagRepository tags;

    /**
     * 작성자에 관계없이 관리자 목록을 조회한다.
     *
     * @param filter 목록 조건
     * @return 회차별 목록
     */
    @Transactional(readOnly = true)
    public Page<Summary> list(AdminProblemSearchRequest filter) {
        return query.search(filter).map(revision -> summary(problem(revision.getProblemId()), revision));
    }

    /**
     * 보관 여부와 관계없이 작성자의 회차 이력을 조회한다.
     *
     * @param problemId 문제
     * @param author 작성자
     * @param page 페이지
     * @param size 페이지 크기
     * @param sort 회차 정렬
     * @return 회차 메타데이터
     * @throws ProblemException 페이지·정렬·식별자가 잘못되면 {@code COMMON_INVALID_REQUEST},
     *     문제가 없으면 {@code PROBLEM_NOT_FOUND}, 작성자가 아니면 {@code AUTH_ACCESS_DENIED}
     */
    @Transactional(readOnly = true)
    public Page<Summary> history(long problemId, long author, int page, int size, String sort) {
        if (page < 0 || size < 1 || size > 100 || (long)page * size > Integer.MAX_VALUE || sort == null
            || !sort.matches("revisionNumber,(asc|desc)")) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        var problem = owned(problemId, author);
        var pageable
            = PageRequest.of(page, size, Sort.by(Sort.Direction.fromString(sort.split(",")[1]), "revisionNumber"));
        return revisions.findByProblemId(problemId, pageable).map(revision -> summary(problem, revision));
    }

    /**
     * 작성자의 최신 또는 지정 회차 자료를 원격 조회용 스냅샷으로 고정한다.
     *
     * @param problemId 소유 문제
     * @param revisionId 지정 회차 또는 최신 조회 시 null
     * @param author 작성자
     * @return 전체 원문 참조와 언어별 검증
     * @throws ProblemException 문제 식별자가 잘못되면 {@code COMMON_INVALID_REQUEST},
     *     문제·회차가 없으면 {@code PROBLEM_NOT_FOUND}·{@code PROBLEM_REVISION_NOT_FOUND},
     *     작성자가 아니면 {@code AUTH_ACCESS_DENIED}
     */
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        readOnly = true
    )
    public Detail detail(long problemId, Long revisionId, long author) {
        var problem = owned(problemId, author);
        var revision
            = (revisionId == null ? revisions.findTopByProblemIdOrderByRevisionNumberDesc(problemId)
                : revisions.findById(revisionId))
                .filter(value -> value.getProblemId() == problemId)
                .orElseThrow(() -> new ProblemException(ProblemErrorCode.PROBLEM_REVISION_NOT_FOUND));
        var body = bodies.findById(revision.getBodyAssetId()).orElseThrow();
        var cases = entries.findByProblemRevisionIdOrderByTestOrder(revision.getId()).stream().map(entry -> {
            var test = tests.findById(entry.getTestAssetId()).orElseThrow();
            return new TestFile(
                test.getId(),
                entry.isSample(),
                new File(test.getObjectKey(), test.getObjectVersion(), test.getSha256(), test.getSizeBytes())
            );
        }).toList();
        var history = validations.findByProblemRevisionIdOrderByIdDesc(revision.getId());
        var latest = new LinkedHashMap<String, ProblemValidation>();
        var selected = new LinkedHashMap<String, ProblemValidation>();
        history.forEach(value -> {
            latest.putIfAbsent(value.getLanguage(), value);
            if ("PASSED".equals(value.getResultStatus())) {
                selected.putIfAbsent(value.getLanguage(), value);
            }
        });
        latest.forEach(selected::putIfAbsent);
        List<Language> languages = new ArrayList<>();
        latest.forEach((language, request) -> {
            var code = codes.findById(selected.get(language).getCodeAssetId()).orElseThrow();
            languages
                .add(
                    new Language(
                        language,
                        request.getId(),
                        !request.isPending(),
                        new File(code.getObjectKey(), code.getObjectVersion(), code.getSha256(), code.getSizeBytes())
                    )
                );
        });
        return new Detail(
            summary(problem, revision),
            new File(body.getObjectKey(), body.getObjectVersion(), body.getSha256(), body.getSizeBytes()),
            tags
                .findByProblemRevisionIdOrderByTagId(revision.getId())
                .stream()
                .map(tag -> tag.getTagId().toString())
                .toList(),
            cases,
            List.copyOf(languages)
        );
    }

    /**
     * 문제와 회차의 메타데이터를 조합한다.
     *
     * @param problem 소유 문제
     * @param revision 조회 회차
     * @return 조회 시점의 사용 회차와 통과 언어
     */
    private Summary summary(Problem problem, ProblemRevision revision) {
        var current = validations.currentNumber(problem.getId());
        Long currentId
            = current == null ? null
                : revisions.findByProblemIdAndRevisionNumber(problem.getId(), current).orElseThrow().getId();
        return new Summary(problem, revision, currentId, validations.languages(revision.getId()));
    }

    /**
     * 양수 식별자로 문제를 조회한다.
     *
     * @param id 문제 식별자
     * @return 문제
     * @throws ProblemException 잘못된 식별자 또는 문제 없음
     */
    private Problem problem(long id) {
        if (id <= 0) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        return problems.findById(id).orElseThrow(() -> new ProblemException(ProblemErrorCode.PROBLEM_NOT_FOUND));
    }

    /**
     * 작성 관리자만 원문과 이력을 조회하도록 검사한다.
     *
     * @param id 문제
     * @param author 요청 관리자
     * @return 권한이 확인된 문제
     * @throws ProblemException 작성자 불일치
     */
    private Problem owned(long id, long author) {
        var result = problem(id);
        if (result.getAuthorId() != author) {
            throw new ProblemException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        return result;
    }

    /** 문제와 고정 회차의 조회 메타데이터. */
    public record Summary(Problem problem, ProblemRevision revision, Long current, List<String> languages) {
    }
    /** 언어별 최신 요청과 표시할 고정 코드 참조. */
    public record Language(String language, long validationId, boolean applied, File code) {
    }
    /** 원격 호출 전에 확정한 불변 파일 참조. */
    public record Detail(
        Summary summary,
        File body,
        List<String> tags,
        List<TestFile> tests,
        List<Language> languages
    ) {
    }
}
