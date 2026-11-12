package com.backtoback.judge.run.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.judge.run.dto.RunRequest;
import com.backtoback.judge.run.dto.RunResponse;
import com.backtoback.judge.run.service.RunService;
import com.backtoback.judge.submission.dto.SubmissionCreateRequest;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/** Gateway에서 인증한 회원의 일반 문제 Run을 제공한다. */
@RestController
@RequiredArgsConstructor
public class RunController {
    private final RunService runs;

    /** §3.1.8 코드 실행 — 요청 안에서 결과를 반환하며 제출 이력을 만들지 않는다. */
    @PostMapping("/api/v1/runs")
    public ResponseEntity<ApiResponse<RunResponse>> run(
        @AuthenticationPrincipal GatewayUser user,
        @Valid @RequestBody RunRequest request
    ) {
        return ResponseEntity
            .ok(ApiResponse.success(null, runs.run(SubmissionCreateRequest.parseId(user.userId()), request)));
    }
}
