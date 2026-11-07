package com.backtoback.problem.problem.controller;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.problem.problem.dto.response.JudgingDataResponse;
import com.backtoback.problem.problem.dto.response.JudgingValidationResponse;
import com.backtoback.problem.problem.service.ProblemJudgingStore;

import lombok.RequiredArgsConstructor;

/** Judge에 지정 회차의 사전 검증과 불변 채점 자료를 제공한다. */
@RestController
@Profile("!internal-stub")
@ConditionalOnProperty(
    prefix = "problem.internal-api",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true
)
@RequiredArgsConstructor
public class ProblemJudgingController {
    private final ProblemJudgingStore store;

    /**
     * 지정 회차의 채점 조건을 조회한다(API 명세서 §2.6.1).
     *
     * @param problemId 문제
     * @param revisionId 고정 회차
     * @return 상태와 통과 환경
     */
    @GetMapping("/internal/v1/problems/{problemId}/revisions/{revisionId}/judging/validation")
    public JudgingValidationResponse validation(@PathVariable long problemId, @PathVariable long revisionId) {
        return JudgingValidationResponse.from(store.read(problemId, revisionId));
    }

    /**
     * 지정 회차의 채점 파일 참조를 조회한다(API 명세서 §2.6.2).
     *
     * @param problemId 문제
     * @param revisionId 고정 회차
     * @return 원문 없는 채점 자료
     */
    @GetMapping("/internal/v1/problems/{problemId}/revisions/{revisionId}/judging/data")
    public JudgingDataResponse data(@PathVariable long problemId, @PathVariable long revisionId) {
        return JudgingDataResponse.from(store.read(problemId, revisionId));
    }
}
