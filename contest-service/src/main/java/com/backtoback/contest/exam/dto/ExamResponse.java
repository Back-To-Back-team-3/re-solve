package com.backtoback.contest.exam.dto;

import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.domain.Exam;
import com.backtoback.contest.exam.domain.ExamProblem;

/** 저장된 시험 설정과 고정 문제 목록이다 (API 명세서 §4.1.1). */
public record ExamResponse(
    String examId,
    String studyId,
    String status,
    String examRevision,
    String version,
    String resultRevision,
    OffsetDateTime startsAt,
    OffsetDateTime endsAt,
    String totalScore,
    List<Problem> problems
) {
    /** 저장된 시험과 문제를 공개 응답으로 변환한다. */
    public static ExamResponse from(Exam exam, List<ExamProblem> problems) {
        return new ExamResponse(
            exam.getId().toString(),
            exam.getStudyId().toString(),
            exam.getStatus().name(),
            Integer.toString(exam.getExamRevision()),
            Long.toString(exam.getVersion()),
            Long.toString(exam.getResultRevision()),
            exam.getStartsAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime(),
            exam.getEndsAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime(),
            exam.getTotalScore().toPlainString(),
            problems.stream().sorted(Comparator.comparingInt(ExamProblem::getDisplayOrder)).map(Problem::from).toList()
        );
    }

    /** 시험에 고정한 문제 회차와 배점이다. */
    public record Problem(
        String problemId,
        String problemRevisionId,
        int revisionNumber,
        String score,
        int displayOrder
    ) {
        /** 저장된 문제를 응답으로 변환한다. */
        public static Problem from(ExamProblem problem) {
            return new Problem(
                problem.getProblemId().toString(),
                problem.getProblemRevisionId().toString(),
                problem.getRevisionNumber(),
                problem.getScore().toPlainString(),
                problem.getDisplayOrder()
            );
        }
    }
}
