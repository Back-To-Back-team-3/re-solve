package com.backtoback.problem.problem.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.backtoback.problem.problem.domain.ProblemValidation;

/** 검증 요청의 멱등 기록과 회차별 진행·통과 상태를 조회한다. */
public interface ProblemValidationRepository extends JpaRepository<ProblemValidation, Long> {
    /**
     * 회차의 검증 이력을 최신 요청부터 조회한다.
     *
     * @param revisionId 고정 회차
     * @return 최신순 검증 요청
     */
    List<ProblemValidation> findByProblemRevisionIdOrderByIdDesc(Long revisionId);

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

    /**
     * 문제의 최신 통과 회차 번호를 조회한다.
     *
     * @param problemId 문제 ID
     * @return 최신 통과 회차 번호 또는 null
     */
    @Query("""
        select max(r.revisionNumber) from ProblemRevision r, ProblemValidation v
        where r.problemId=:problemId and r.id=v.problemRevisionId and v.resultStatus='PASSED'
        """)
    Integer currentNumber(long problemId);

    /**
     * 지정 회차의 통과 언어를 조회한다.
     *
     * @param revisionId 회차 ID
     * @return 통과 언어 목록
     */
    @Query("""
        select distinct v.language from ProblemValidation v where v.problemRevisionId=:revisionId
        and v.resultStatus='PASSED' order by v.language
        """)
    List<String> languages(long revisionId);

}
