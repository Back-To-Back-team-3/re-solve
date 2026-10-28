package com.backtoback.judge.judging.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.judge.judging.domain.JudgeJob;

/** {@code JudgeJob} 저장과 식별자 조회를 제공한다. */
public interface JudgeJobRepository extends JpaRepository<JudgeJob, Long> {
    /**
     * 제출의 특정 채점 회차 작업을 조회한다.
     *
     * @param submissionId 제출 ID
     * @param judgeAttempt 채점 회차
     * @return 해당 회차의 작업
     */
    Optional<JudgeJob> findBySubmissionIdAndJudgeAttempt(long submissionId, int judgeAttempt);
}
