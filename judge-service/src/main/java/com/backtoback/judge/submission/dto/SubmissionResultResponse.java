package com.backtoback.judge.submission.dto;

import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.Set;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.judge.judging.domain.JudgeJob;
import com.backtoback.judge.judging.domain.JudgeTestResult;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.judging.service.SubmissionVerdict;
import com.backtoback.judge.submission.domain.Submission;
import com.backtoback.judge.submission.service.SubmissionHistory;

/** 현재 작업 상태와 최신 정상 완료 회차의 결과를 분리한 응답. (API §3.1.3) */
public record SubmissionResultResponse(
    String submissionId,
    String problemId,
    String problemRevisionId,
    String language,
    String context,
    String status,
    int judgeAttempt,
    OffsetDateTime submittedAt,
    EffectiveResult effectiveResult,
    String compileMessage,
    String failureCode
) {

    public static SubmissionResultResponse from(SubmissionHistory history) {
        var submission = history.submission();
        var effective
            = history
                .attempts()
                .stream()
                .filter(value -> value.job().getStatus() == JudgeJob.Status.SUCCEEDED)
                .findFirst()
                .map(EffectiveResult::from)
                .orElse(null);
        String failure = null;
        if (submission.getStatus() == Submission.Status.FAILED) {
            failure
                = history
                    .attempts()
                    .stream()
                    .filter(value -> value.job().getJudgeAttempt() == submission.getJudgeAttempt())
                    .map(value -> value.job().getLastError())
                    .filter(Objects::nonNull)
                    .findFirst()
                    .orElse("ENGINE_UNAVAILABLE");
            if (!Set
                .of(
                    "JUDGE0_UNAVAILABLE",
                    "JUDGE0_TIMEOUT",
                    "LEASE_EXHAUSTED",
                    "DEADLINE_EXCEEDED",
                    "TEST_DATA_UNAVAILABLE"
                )
                .contains(failure)) {
                failure = "ENGINE_UNAVAILABLE";
            }
        }
        return new SubmissionResultResponse(
            submission.getId().toString(),
            Long.toString(submission.getProblemId()),
            Long.toString(submission.getProblemRevisionId()),
            submission.getLanguage(),
            submission.getContext().name(),
            submission.getStatus().name(),
            submission.getJudgeAttempt(),
            submission.getSubmittedAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime(),
            effective,
            effective != null && effective.verdict() == JudgeVerdict.CE ? submission.getCompileMessage() : null,
            failure
        );
    }

    /** 실행 중 결과를 섞지 않고 보존된 정상 완료 회차만 집계한다. */
    public record EffectiveResult(
        int judgeAttempt,
        JudgeVerdict verdict,
        int passedCount,
        int totalCount,
        Integer maxTimeMs,
        Integer maxMemoryKb,
        OffsetDateTime judgedAt
    ) {
        public static EffectiveResult from(SubmissionHistory.Attempt attempt) {
            var results = attempt.tests();
            var verdict = SubmissionVerdict.summarize(results.stream().map(JudgeTestResult::getVerdict).toList());
            Integer time
                = results
                    .stream()
                    .map(JudgeTestResult::getTimeMs)
                    .filter(Objects::nonNull)
                    .max(Integer::compareTo)
                    .orElse(null);
            Integer memory
                = results
                    .stream()
                    .map(JudgeTestResult::getMemoryKb)
                    .filter(Objects::nonNull)
                    .max(Integer::compareTo)
                    .orElse(null);
            return new EffectiveResult(
                attempt.job().getJudgeAttempt(),
                verdict,
                (int)results.stream().filter(value -> value.getVerdict() == JudgeVerdict.AC).count(),
                results.size(),
                verdict == JudgeVerdict.CE ? null : time,
                verdict == JudgeVerdict.CE ? null : memory,
                attempt.job().getFinishedAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime()
            );
        }
    }
}
