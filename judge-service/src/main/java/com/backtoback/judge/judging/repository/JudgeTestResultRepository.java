package com.backtoback.judge.judging.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.judge.judging.domain.JudgeTestResult;

/** {@code JudgeTestResult} 저장과 식별자 조회를 제공한다. */
public interface JudgeTestResultRepository extends JpaRepository<JudgeTestResult, Long> {
    List<JudgeTestResult> findByJudgeJobSubmissionIdOrderByJudgeJobJudgeAttemptAscTestOrderAsc(long submissionId);

    /**
     * 작업에 속한 테스트 결과 자리를 순서대로 조회한다.
     *
     * @param judgeJobId 작업 ID
     * @return 테스트 순번으로 정렬한 결과
     */
    List<JudgeTestResult> findByJudgeJobIdOrderByTestOrder(long judgeJobId);
}
