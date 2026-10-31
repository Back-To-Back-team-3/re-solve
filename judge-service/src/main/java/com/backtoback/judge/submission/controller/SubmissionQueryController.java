package com.backtoback.judge.submission.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.api.PageResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.judge.submission.dto.SubmissionCreateRequest;
import com.backtoback.judge.submission.dto.SubmissionListRequest;
import com.backtoback.judge.submission.dto.SubmissionListResponse;
import com.backtoback.judge.submission.dto.SubmissionSourceResponse;
import com.backtoback.judge.submission.service.SubmissionQueryService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 본인 일반 풀이 조회 API. */
@RestController
@RequiredArgsConstructor
public class SubmissionQueryController {
    private final SubmissionQueryService queries;

    /** API §3.1.4 — 일반 풀이만 제공하며 시험·대회 결과 공개 조건을 우회하지 않는다. */
    @GetMapping("/api/v1/me/submissions")
    public ResponseEntity<ApiResponse<PageResponse<SubmissionListResponse>>> list(
        @AuthenticationPrincipal GatewayUser user,
        @Valid @ModelAttribute SubmissionListRequest request
    ) {
        return ResponseEntity
            .ok(ApiResponse.success(null, queries.list(SubmissionCreateRequest.parseId(user.userId()), request)));
    }

    /** API §3.1.5 — 원본 코드 응답은 브라우저·중간 캐시에 보관하지 않는다. */
    @GetMapping("/api/v1/submissions/{submissionId}/source")
    public ResponseEntity<ApiResponse<SubmissionSourceResponse>> source(
        @AuthenticationPrincipal GatewayUser user,
        @PathVariable String submissionId
    ) {
        var result
            = queries
                .source(SubmissionCreateRequest.parseId(user.userId()), SubmissionCreateRequest.parseId(submissionId));
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(ApiResponse.success(null, result));
    }
}
