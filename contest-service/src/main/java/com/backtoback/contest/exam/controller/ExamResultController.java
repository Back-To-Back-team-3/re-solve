package com.backtoback.contest.exam.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.contest.exam.dto.ExamMyResultResponse;
import com.backtoback.contest.exam.dto.ExamStandingsResponse;
import com.backtoback.contest.exam.service.ExamResultQueryService;

import lombok.RequiredArgsConstructor;

/** 시험의 본인 결과와 공개 순위를 제공한다. */
@RestController
@RequiredArgsConstructor
public class ExamResultController {
    private final ExamResultQueryService results;

    /** 4.2.7 내 시험 결과 — 전체 종료 전에는 본인 결과도 숨긴다 (API 명세서 4.2.7). */
    @GetMapping("/api/v1/exams/{examId}/results/me")
    public ResponseEntity<ApiResponse<ExamMyResultResponse>> me(
        @PathVariable String examId,
        @AuthenticationPrincipal GatewayUser user
    ) {
        return ResponseEntity
            .ok(ApiResponse.success(null, ExamMyResultResponse.from(results.me(examId, user.userId()))));
    }

    /** 4.2.8 시험 결과 순위 — 같은 결과 버전의 순위 페이지를 제공한다 (API 명세서 4.2.8). */
    @GetMapping("/api/v1/exams/{examId}/results")
    public ResponseEntity<ApiResponse<ExamStandingsResponse>> standings(
        @PathVariable String examId,
        @AuthenticationPrincipal GatewayUser user,
        @RequestParam(required = false) String after,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity
            .ok(
                ApiResponse
                    .success(null, ExamStandingsResponse.from(results.standings(examId, user.userId(), after, size)))
            );
    }
}
