package com.backtoback.contest.exam.controller;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.contest.exam.service.ExamResultQueryService;
import com.backtoback.contest.exam.service.ExamResultQueryService.Me;
import com.backtoback.contest.exam.service.ExamResultQueryService.Page;

import lombok.RequiredArgsConstructor;

/** 시험의 본인 결과와 공개 순위를 제공한다. */
@RestController
@RequiredArgsConstructor
public class ExamResultController {
    private final ExamResultQueryService results;

    /** 시험 전체 종료 전에는 본인 결과도 숨긴다. */
    @GetMapping("/api/v1/exams/{examId}/results/me")
    public ApiResponse<Me> me(@PathVariable String examId, @AuthenticationPrincipal GatewayUser user) {
        return ApiResponse.success(null, results.me(examId, user.userId()));
    }

    /** 같은 결과 버전의 순위 페이지를 조회한다. */
    @GetMapping("/api/v1/exams/{examId}/results")
    public ApiResponse<Page> standings(
        @PathVariable String examId,
        @AuthenticationPrincipal GatewayUser user,
        @RequestParam(required = false) String after,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ApiResponse.success(null, results.standings(examId, user.userId(), after, size));
    }
}
