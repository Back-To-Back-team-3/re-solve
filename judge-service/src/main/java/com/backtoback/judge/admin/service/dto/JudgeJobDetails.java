package com.backtoback.judge.admin.service.dto;

import java.time.LocalDateTime;
import java.util.List;

/** 원문 없이 조회한 작업·시도·테스트의 스칼라 상태. */
public record JudgeJobDetails(JudgeJobState job, List<Run> runs, List<Test> testResults) {
    /** 실행 시도 종료 이력. */
    public record Run(
        int tryCount,
        String result,
        String errorMessage,
        LocalDateTime startedAt,
        LocalDateTime finishedAt
    ) {
    }

    /** 원문을 제외한 테스트별 실행 상태. */
    public record Test(int testOrder, String tokenStatus, String judge0Token, String verdict) {
    }
}
