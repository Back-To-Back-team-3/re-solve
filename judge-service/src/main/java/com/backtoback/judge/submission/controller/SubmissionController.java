package com.backtoback.judge.submission.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.judge.submission.dto.SubmissionCreateRequest;
import com.backtoback.judge.submission.dto.SubmissionCreateResponse;
import com.backtoback.judge.submission.service.SubmissionAcceptanceService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** 인증 회원의 일반 제출을 접수하며 회원 ID를 본문에서 받지 않는다. */
@RestController
@RequiredArgsConstructor
public class SubmissionController {
    private final SubmissionAcceptanceService acceptance;

    /**
     * 일반 풀이 제출을 비동기 작업으로 접수한다. (API 명세서 §3.1.2)
     *
     * @param user Gateway 인증 principal
     * @param key 멱등 키
     * @param request 사용자 코드 원본
     * @return 원래 접수 시각·제출 ID를 포함한 202 응답
     */
    @PostMapping("/api/v1/submissions")
    public ResponseEntity<ApiResponse<SubmissionCreateResponse>> create(
        @AuthenticationPrincipal GatewayUser user,
        @RequestHeader("Idempotency-Key") String key,
        @Valid @RequestBody SubmissionCreateRequest request
    ) {
        var response = acceptance.create(SubmissionCreateRequest.parseId(user.userId()), key, request);
        return ResponseEntity.accepted().body(ApiResponse.success("제출이 접수되었습니다.", response));
    }
}
