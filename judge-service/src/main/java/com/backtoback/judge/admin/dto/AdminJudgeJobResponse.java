package com.backtoback.judge.admin.dto;

import java.time.OffsetDateTime;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.judge.admin.service.dto.JudgeJobState;

/** 관리자 목록에 공개하는 작업 요약. */
public record AdminJudgeJobResponse(
    String judgeJobId,
    String submissionId,
    int judgeAttempt,
    String status,
    int tryCount,
    String failureCode,
    String memberId,
    String context,
    String contextId,
    String problemRevisionId,
    OffsetDateTime deadlineAt,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    /**
     * DB 식별자와 서비스 시각을 외부 응답으로 변환한다.
     *
     * @param job 조회한 작업 상태
     * @return 원문과 임대 토큰을 제외한 요약
     */
    public static AdminJudgeJobResponse from(JudgeJobState job) {
        return new AdminJudgeJobResponse(
            Long.toString(job.id()),
            Long.toString(job.submissionId()),
            job.judgeAttempt(),
            job.status(),
            job.tryCount(),
            job.failureCode(),
            Long.toString(job.memberId()),
            job.context(),
            job.contextId() == null ? null : job.contextId().toString(),
            Long.toString(job.revisionId()),
            job.deadlineAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime(),
            job.createdAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime(),
            job.updatedAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime()
        );
    }
}
