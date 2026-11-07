package com.backtoback.problem.problem.dto.response;

import java.util.List;

import com.backtoback.problem.problem.service.ValidationProgressStore.Snapshot;
import com.fasterxml.jackson.annotation.JsonInclude;

/** 관리자에게 공개하는 현재 시도의 측정값과 Problem 반영 정보. */
@JsonInclude(JsonInclude.Include.ALWAYS)
public record AdminValidationProgressResponse(
    String validationId,
    String problemId,
    String problemRevisionId,
    String language,
    String status,
    List<TestResult> tests,
    Integer maxTimeMs,
    String message,
    boolean resultApplied,
    List<String> supportedLanguages,
    String currentProblemRevisionId
) {
    /**
     * 작업 미생성과 원격 진행 결과를 관리자 응답으로 변환한다.
     *
     * @param local Problem 반영 상태
     * @param progress Judge 작업 정보 또는 작업 미생성 시 null
     * @return 관리자 진행 응답
     */
    public static AdminValidationProgressResponse from(Snapshot local, JudgeValidationProgressResponse progress) {
        return new AdminValidationProgressResponse(
            local.id(),
            local.problemId(),
            local.revisionId(),
            local.language(),
            progress == null ? "REQUESTED" : progress.status(),
            progress == null ? List.of()
                : progress
                    .tests()
                    .stream()
                    .map(test -> new TestResult(test.testOrder(), test.verdict(), test.timeMs(), test.memoryKb()))
                    .toList(),
            progress == null ? null : progress.maxTimeMs(),
            progress == null ? null : progress.message(),
            local.applied(),
            local.languages(),
            local.current()
        );
    }

    /** 관리자에게 공개하는 테스트별 순서·판정·측정값이다. */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record TestResult(int testOrder, String verdict, Integer timeMs, Integer memoryKb) {
    }
}
