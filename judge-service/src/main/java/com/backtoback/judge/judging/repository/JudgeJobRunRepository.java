package com.backtoback.judge.judging.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.judge.judging.domain.JudgeJobRun;

/** {@code JudgeJobRun} 저장과 식별자 조회를 제공한다. */
public interface JudgeJobRunRepository extends JpaRepository<JudgeJobRun, Long> {
    /**
     * 작업의 실행 시도 이력을 시도 번호순으로 조회한다.
     *
     * @param judgeJobId 작업 ID
     * @return 시도 번호순 불변 이력
     */
    List<JudgeJobRun> findByJudgeJobIdOrderByTryCount(long judgeJobId);
}
