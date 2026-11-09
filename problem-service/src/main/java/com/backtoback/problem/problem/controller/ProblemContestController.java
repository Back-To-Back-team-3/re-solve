package com.backtoback.problem.problem.controller;

import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.problem.problem.config.InternalProblemServiceCondition;
import com.backtoback.problem.problem.dto.request.ContestProblemLinkRequest;
import com.backtoback.problem.problem.dto.response.ContestProblemLinkResponse;
import com.backtoback.problem.problem.service.ProblemContestService;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Contest가 권한을 확인한 대회 연결 API를 제공한다. */
@RestController
@Profile("!internal-stub")
@Conditional(InternalProblemServiceCondition.class)
@RequiredArgsConstructor
public class ProblemContestController {
    private final ProblemContestService service;

    /**
     * EXAM_ONLY 문제를 대회에 연결한다(API 명세서 §2.6.9).
     *
     * @param problemId 대상 문제
     * @param body 대회와 고정 회차
     * @param request 감사 주소
     * @return 최초 연결 결과
     */
    @PutMapping("/internal/v1/problems/{problemId}/contest")
    public ContestProblemLinkResponse link(
        @PathVariable long problemId,
        @Valid @RequestBody ContestProblemLinkRequest body,
        HttpServletRequest request
    ) {
        return ContestProblemLinkResponse.from(service.link(problemId, body, request.getRemoteAddr()));
    }
}
