package com.backtoback.contest.exam.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.contest.exam.service.ExamSubmissionService;
import com.backtoback.contest.exam.service.ExamSubmissionService.Request;
import com.backtoback.contest.exam.service.ExamSubmissionService.Response;

import lombok.RequiredArgsConstructor;

/** 시험 참가자의 직접 제출 접수를 제공한다. */
@RestController
@RequiredArgsConstructor
public class ExamSubmissionController {
    private final ExamSubmissionService submissions;

    /** API 명세서 §4.2.5의 직접 제출을 접수한다. */
    @PostMapping("/api/v1/exams/{examId}/submissions")
    public ResponseEntity<ApiResponse<Response>> submit(
        @PathVariable String examId,
        @AuthenticationPrincipal GatewayUser user,
        @RequestBody Request request
    ) {
        return ResponseEntity
            .accepted()
            .body(ApiResponse.success("제출이 접수되었습니다.", submissions.submit(examId, user.userId(), request)));
    }
}
