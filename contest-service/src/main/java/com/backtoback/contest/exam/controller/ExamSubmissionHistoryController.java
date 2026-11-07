package com.backtoback.contest.exam.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.contest.exam.dto.ExamSubmissionHistoryResponse;
import com.backtoback.contest.exam.service.ExamSubmissionHistoryService;

import lombok.RequiredArgsConstructor;

/** 본인의 시험 접수 이력을 제공한다. */
@RestController
@RequiredArgsConstructor
public class ExamSubmissionHistoryController {
    private final ExamSubmissionHistoryService history;

    /** 4.2.9 내 시험 제출 내역 — 종료 전 채점 결과를 숨긴다 (API 명세서 4.2.9). */
    @GetMapping("/api/v1/exams/{examId}/submissions")
    public ResponseEntity<ApiResponse<ExamSubmissionHistoryResponse>> read(
        @PathVariable String examId,
        @AuthenticationPrincipal GatewayUser user,
        @RequestParam(required = false) String problemId,
        @RequestParam(required = false) String after,
        @RequestParam(defaultValue = "20") int size
    ) {
        return ResponseEntity
            .ok(
                ApiResponse
                    .success(
                        null,
                        ExamSubmissionHistoryResponse.from(history.read(examId, user.userId(), problemId, after, size))
                    )
            );
    }
}
