package com.backtoback.judge.admin.dto;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.List;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.judge.admin.service.dto.JudgeJobDetails;

/** 관리자에게 제공하는 작업·실행 시도·테스트 상태 상세. */
public record AdminJudgeJobDetailResponse(
    String judgeJobId,
    String submissionId,
    int judgeAttempt,
    String status,
    int tryCount,
    OffsetDateTime leaseUntil,
    OffsetDateTime nextRunAt,
    OffsetDateTime deadlineAt,
    String lastError,
    List<Run> runs,
    List<Test> testResults
) {
    /** 실행 시도 종료 응답. */
    public record Run(
        int tryCount,
        String result,
        String errorMessage,
        OffsetDateTime startedAt,
        OffsetDateTime finishedAt
    ) {
    }

    /** 테스트 실행 상태 응답. */
    public record Test(int testOrder, String tokenStatus, String judge0Token, String verdict) {
    }

    /**
     * 상태 조회 결과의 ID와 시각을 공개 응답으로 변환한다.
     *
     * @param details 검증한 작업 상세
     * @return 코드·테스트 원문·임대 토큰을 제외한 상세
     */
    public static AdminJudgeJobDetailResponse from(JudgeJobDetails details) {
        var job = details.job();
        return new AdminJudgeJobDetailResponse(
            Long.toString(job.id()),
            Long.toString(job.submissionId()),
            job.judgeAttempt(),
            job.status(),
            job.tryCount(),
            time(job.leaseUntil()),
            time(job.nextRunAt()),
            time(job.deadlineAt()),
            job.failureCode(),
            details
                .runs()
                .stream()
                .map(
                    run -> new Run(
                        run.tryCount(),
                        run.result(),
                        run.errorMessage(),
                        time(run.startedAt()),
                        time(run.finishedAt())
                    )
                )
                .toList(),
            details
                .testResults()
                .stream()
                .map(test -> new Test(test.testOrder(), test.tokenStatus(), test.judge0Token(), test.verdict()))
                .toList()
        );
    }

    /**
     * 저장 시각을 서울 시간대 응답으로 변환한다.
     *
     * @param value 저장된 시각 또는 {@code null}
     * @return 시간대가 있는 시각 또는 {@code null}
     */
    private static OffsetDateTime time(LocalDateTime value) {
        return value == null ? null : value.atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime();
    }
}
