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

    /**
     * 본인 일반 제출 목록을 조회한다. (API 명세서 §3.1.4)
     *
     * @param user Gateway에서 인증한 회원
     * @param request 검증할 목록 필터와 페이지 조건
     * @return 일반 제출 요약 페이지
     */
    @GetMapping("/api/v1/me/submissions")
    public ResponseEntity<ApiResponse<PageResponse<SubmissionListResponse>>> list(
        @AuthenticationPrincipal GatewayUser user,
        @Valid @ModelAttribute SubmissionListRequest request
    ) {
        return ResponseEntity
            .ok(ApiResponse.success(null, queries.list(SubmissionCreateRequest.parseId(user.userId()), request)));
    }

    /**
     * 본인 원본 코드를 조회하며 응답 캐시를 금지한다. (API 명세서 §3.1.5)
     *
     * @param user Gateway에서 인증한 회원
     * @param submissionId 조회할 제출 식별자
     * @return 캐시 금지 헤더를 포함한 원본 코드 응답
     */
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
