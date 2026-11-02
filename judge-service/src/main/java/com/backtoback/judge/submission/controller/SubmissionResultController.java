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

/** 본인 일반 제출의 현재 상태와 최신 정상 완료 결과를 제공한다. */
@RestController
@RequiredArgsConstructor
public class SubmissionResultController {
    private final SubmissionHistoryReader history;

    /**
     * 현재 작업 상태와 최신 정상 완료 결과를 분리해 조회한다. (API 명세서 §3.1.3)
     *
     * @param user Gateway에서 인증한 회원
     * @param submissionId 조회할 제출 식별자
     * @return 현재 상태와 유효 결과 응답
     */
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
