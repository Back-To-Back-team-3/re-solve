package com.backtoback.problem.problem.controller;

import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.problem.problem.config.InternalProblemServiceCondition;
import com.backtoback.problem.problem.dto.response.ProblemOperationResponse;
import com.backtoback.problem.problem.service.InternalProblemService;

import lombok.RequiredArgsConstructor;

/** 내부 연동용 stub 프로필의 기존 상태 작업 응답을 유지한다. */
@RestController
@Profile("internal-stub")
@Conditional(InternalProblemServiceCondition.class)
@RequiredArgsConstructor
public class InternalProblemOperationStubController {
    private final InternalProblemService service;

    /**
     * 개발용 원래 작업 결과를 반환한다. API 명세서 §2.6.10을 따른다.
     *
     * @param problemId 대상 문제
     * @param operationId 원래 작업 ID
     * @return 고정된 연동용 응답
     */
    @GetMapping("/internal/v1/problems/{problemId}/operations/{operationId}")
    public ProblemOperationResponse getOperation(@PathVariable String problemId, @PathVariable String operationId) {
        return service.getOperation(problemId, operationId);
    }
}
