package com.backtoback.contest.exam.dto;

import java.util.List;

import com.backtoback.contest.exam.service.ExamResultQueryService;

/** 공개 시점에 따라 제한되는 본인 시험 결과다 (API 명세서 4.2.7). */
public record ExamMyResultResponse(
    String resultStatus,
    String resultRevision,
    String score,
    Integer rank,
    boolean isRanked,
    Progress progress,
    List<Problem> problems
) {
    public static ExamMyResultResponse from(ExamResultQueryService.Me result) {
        Progress progress
            = result.progress() == null ? null
                : new Progress(result.progress().terminalCount(), result.progress().totalSubmissionCount());
        return new ExamMyResultResponse(
            result.resultStatus(),
            result.resultRevision(),
            result.score(),
            result.rank(),
            result.isRanked(),
            progress,
            result.problems().stream().map(Problem::from).toList()
        );
    }

    public record Progress(int terminalCount, int totalSubmissionCount) {
    }

    public record Problem(
        String problemId,
        String lastValidExamSubmissionId,
        String verdict,
        String score,
        Integer passedCount,
        Integer totalCount,
        boolean pending
    ) {
        static Problem from(ExamResultQueryService.Problem result) {
            return new Problem(
                result.problemId(),
                result.lastValidExamSubmissionId(),
                result.verdict(),
                result.score(),
                result.passedCount(),
                result.totalCount(),
                result.pending()
            );
        }
    }
}
