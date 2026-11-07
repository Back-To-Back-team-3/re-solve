package com.backtoback.problem.problem.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.problem.problem.domain.ProblemValidation;

/** 검증 요청의 멱등 기록과 회차별 진행·통과 상태를 조회한다. */
public interface ProblemValidationRepository extends JpaRepository<ProblemValidation, Long> {
    /**
     * 작성 관리자와 멱등키의 원래 요청을 조회한다.
     *
     * @param authorId 작성 관리자
     * @param key 멱등키
     * @return 원래 요청
     */
    Optional<ProblemValidation> findByRequestedByAndIdempotencyKey(Long authorId, String key);

    /**
     * 같은 회차·언어의 검증 이력을 조회한다.
     *
     * @param revisionId 고정 회차
     * @param language 언어
     * @return 검증 이력
     */
    List<ProblemValidation> findByProblemRevisionIdAndLanguage(Long revisionId, String language);
}
