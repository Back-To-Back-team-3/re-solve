package com.backtoback.problem.problem.controller;

import java.util.List;

import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.problem.problem.config.InternalProblemServiceCondition;
import com.backtoback.problem.problem.dto.response.ProblemSummaryListResponse;
import com.backtoback.problem.problem.service.ProblemReadStore;

import lombok.RequiredArgsConstructor;

/** 내부 편입 용도별 요약을 실제 저장 자료에서 제공한다. */
@RestController
@Profile("!internal-stub")
@Conditional(InternalProblemServiceCondition.class)
@RequiredArgsConstructor
public class ProblemSummaryController {
    private final ProblemReadStore store;

    /**
     * 요청 ID 순서로 가용 여부를 반환한다(API 명세서 §2.6.5).
     *
     * @param ids 문제 ID
     * @param purpose 편입 용도
     * @return 내부 요약
     */
    @GetMapping("/internal/v1/problems/summaries")
    public ProblemSummaryListResponse summaries(@RequestParam List<Long> ids, @RequestParam String purpose) {
        return ProblemSummaryListResponse.from(store.summaries(ids, purpose));
    }
}
