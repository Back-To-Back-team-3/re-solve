package com.backtoback.problem.problem.controller;

import java.util.List;

import org.springframework.context.annotation.Conditional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.problem.problem.config.InternalProblemStubCondition;
import com.backtoback.problem.problem.dto.response.ProblemSummaryListResponse;
import com.backtoback.problem.problem.service.InternalProblemService;

import lombok.RequiredArgsConstructor;

/** 명시적으로 선택한 개발 Stub의 요약 경로를 제공한다. */
@RestController
@Conditional(InternalProblemStubCondition.class)
@RequiredArgsConstructor
public class ProblemSummaryStubController {
    private final InternalProblemService service;

    /**
     * 개발 시나리오의 선택 요약을 조회한다.
     *
     * @param ids 문제 ID
     * @param purpose 편입 용도
     * @return 개발용 요약
     */
    @GetMapping("/internal/v1/problems/summaries")
    public ProblemSummaryListResponse summaries(@RequestParam List<String> ids, @RequestParam String purpose) {
        return service.getSummaries(ids, purpose);
    }
}
