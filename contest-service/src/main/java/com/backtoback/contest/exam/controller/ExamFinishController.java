package com.backtoback.contest.exam.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.contest.exam.dto.ExamFinishResponse;
import com.backtoback.contest.exam.service.ExamAutoSubmissionService;

import lombok.RequiredArgsConstructor;

/** 입장한 참가자의 개인 시험 종료를 제공한다. */
@RestController
@RequiredArgsConstructor
public class ExamFinishController {
    private final ExamAutoSubmissionService submissions;

    /** 4.2.6 시험 수동 종료 — 최신 임시 저장본의 자동 제출과 종료를 함께 처리한다 (API 명세서 4.2.6). */
    @PostMapping("/api/v1/exams/{examId}/finish")
    public ResponseEntity<ApiResponse<ExamFinishResponse>> finish(
        @PathVariable String examId,
        @AuthenticationPrincipal GatewayUser user
    ) {
        return ResponseEntity
            .ok(ApiResponse.success(null, ExamFinishResponse.from(submissions.finish(examId, user.userId()))));
    }
}
