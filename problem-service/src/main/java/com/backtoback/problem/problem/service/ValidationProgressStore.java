package com.backtoback.problem.problem.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.repository.ProblemRepository;
import com.backtoback.problem.problem.repository.ProblemRevisionRepository;
import com.backtoback.problem.problem.repository.ProblemValidationRepository;

import lombok.RequiredArgsConstructor;

/** Judge 조회 전에 한 시점의 문제 사용 정보와 작성 권한을 확인한다. */
@Service
@RequiredArgsConstructor
public class ValidationProgressStore {
    private final ProblemValidationRepository validations;
    private final ProblemRevisionRepository revisions;
    private final ProblemRepository problems;

    /**
     * 작성 관리자에게 공개할 반영 상태를 조회한다.
     *
     * @param id 검증 ID
     * @param author 관리자 ID
     * @return 원격 조회와 대조할 요청 정보
     * @throws ProblemException 요청 없음은 {@code PROBLEM_VALIDATION_NOT_FOUND}, 작성자 불일치는 {@code AUTH_ACCESS_DENIED}
     */
    @Transactional(readOnly = true)
    public Snapshot read(long id, long author) {
        var validation
            = validations
                .findById(id)
                .orElseThrow(() -> new ProblemException(ProblemErrorCode.PROBLEM_VALIDATION_NOT_FOUND));
        var revision = revisions.findById(validation.getProblemRevisionId()).orElseThrow();
        var problem = problems.findById(revision.getProblemId()).orElseThrow();
        if (problem.getAuthorId() != author) {
            throw new ProblemException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        var current = validations.currentNumber(problem.getId());
        return new Snapshot(
            Long.toString(id),
            problem.getId().toString(),
            revision.getId().toString(),
            validation.getLanguage(),
            !validation.isPending(),
            validations.languages(revision.getId()),
            current == null ? null
                : revisions.findByProblemIdAndRevisionNumber(problem.getId(), current).orElseThrow().getId().toString()
        );
    }

    /** 원격 조회 전에 같은 트랜잭션에서 확인한 요청과 반영 상태다. */
    public record Snapshot(
        String id,
        String problemId,
        String revisionId,
        String language,
        boolean applied,
        List<String> languages,
        String current
    ) {
    }
}
