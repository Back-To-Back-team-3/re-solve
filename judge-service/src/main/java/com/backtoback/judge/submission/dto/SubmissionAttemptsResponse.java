package com.backtoback.judge.submission.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.judge.judging.domain.JudgeJob;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.submission.service.SubmissionHistory;

/** 본인 일반 제출의 보존된 채점 회차 목록. */
public record SubmissionAttemptsResponse(List<Attempt> content) {
    public static SubmissionAttemptsResponse from(SubmissionHistory history) {
        return new SubmissionAttemptsResponse(history.attempts().stream().map(Attempt::from).toList());
    }

    public record Attempt(
        int judgeAttempt,
        String trigger,
        String status,
        JudgeVerdict verdict,
        Integer passedCount,
        Integer totalCount,
        Integer maxTimeMs,
        Integer maxMemoryKb,
        OffsetDateTime createdAt,
        OffsetDateTime finishedAt
    ) {
        public static Attempt from(SubmissionHistory.Attempt attempt) {
            var job = attempt.job();
            var result
                = job.getStatus() == JudgeJob.Status.SUCCEEDED ? SubmissionResultResponse.EffectiveResult.from(attempt)
                    : null;
            // 일반 풀이는 재채점을 허용하지 않으므로 추가 회차는 운영 재처리다. (API §3.2.3·§3.2.4)
            return new Attempt(
                job.getJudgeAttempt(),
                job.getJudgeAttempt() == 1 ? "INITIAL" : "REPROCESS",
                job.getStatus().name(),
                result == null ? null : result.verdict(),
                result == null ? null : result.passedCount(),
                result == null ? null : result.totalCount(),
                result == null ? null : result.maxTimeMs(),
                result == null ? null : result.maxMemoryKb(),
                job.getCreatedAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime(),
                job.getFinishedAt() == null ? null
                    : job.getFinishedAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime()
            );
        }
    }
}
