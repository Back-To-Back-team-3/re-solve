package com.backtoback.problem.problem.controller;

import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.problem.problem.dto.response.ProblemOperationResponse;
import com.backtoback.problem.problem.service.ProblemOperationService;

import lombok.RequiredArgsConstructor;

/** Contest가 대사할 원래 상태 작업을 내부 네트워크에 제공한다. */
@RestController
@Profile("!internal-stub")
@RequiredArgsConstructor
public class InternalProblemOperationController {
    private final ProblemOperationService service;

    /**
     * 작업 ID와 문제 소속으로 당시 결과를 반환한다. API 명세서 §2.6.10을 따른다.
     *
     * @param problemId 대상 문제
     * @param operationId 원래 작업 ID
     * @return 공통 래퍼가 없는 작업 스냅샷
     */
    @GetMapping("/internal/v1/problems/{problemId}/operations/{operationId}")
    public ProblemOperationResponse getOperation(@PathVariable long problemId, @PathVariable String operationId) {
        return ProblemOperationResponse.from(service.find(problemId, operationId));
    }
}
