package com.backtoback.judge.submission.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.judge.submission.dto.SubmissionCreateRequest;
import com.backtoback.judge.submission.dto.SubmissionResultResponse;
import com.backtoback.judge.submission.service.SubmissionHistoryReader;

import lombok.RequiredArgsConstructor;

@RestController
@RequiredArgsConstructor
public class SubmissionResultController {
    private final SubmissionHistoryReader history;

    /** API §3.1.3 — 시스템 실패를 오답으로 변환하지 않고 최신 유효 회차를 별도로 제공한다. */
    @GetMapping("/api/v1/submissions/{submissionId}")
    public ResponseEntity<ApiResponse<SubmissionResultResponse>> result(
        @AuthenticationPrincipal GatewayUser user,
        @PathVariable String submissionId
    ) {
        var snapshot
            = history
                .read(SubmissionCreateRequest.parseId(user.userId()), SubmissionCreateRequest.parseId(submissionId));
        return ResponseEntity.ok(ApiResponse.success(SubmissionResultResponse.from(snapshot)));
    }
}
