package com.backtoback.problem.problem.service;

import java.util.List;

import com.backtoback.problem.problem.domain.ProblemScope;
import com.backtoback.problem.problem.dto.FunctionSpec;
import com.backtoback.problem.problem.dto.request.TestCaseRequest;

/** 업로드와 요청 해시 계산을 마친 문제 회차 저장 입력. */
public record ProblemRegistrationCommand(
    Long problemId,
    Integer expectedRowVersion,
    ProblemScope scope,
    Long authorId,
    String idempotencyKey,
    String requestHash,
    Long bodyAssetId,
    String bodySha256,
    long bodySizeBytes,
    String title,
    int difficulty,
    FunctionSpec functionSpec,
    List<TestCaseRequest> testCases,
    int timeLimitMs,
    int memoryLimitKb,
    int wallTimeLimitMs,
    int outputLimitKb,
    List<Long> tagIds,
    String description,
    String constraints,
    String examples
) {
}
