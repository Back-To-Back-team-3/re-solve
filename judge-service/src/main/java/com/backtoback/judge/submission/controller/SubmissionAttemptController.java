package com.backtoback.judge.submission.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.judge.submission.dto.SubmissionAttemptsResponse;
import com.backtoback.judge.submission.dto.SubmissionCreateRequest;
import com.backtoback.judge.submission.dto.SubmissionTestResultsResponse;
import com.backtoback.judge.submission.service.SubmissionHistoryReader;
import com.backtoback.judge.submission.service.SubmissionTestResultService;

import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;

/** 본인 일반 제출의 회차와 공개 범위가 적용된 테스트 결과 조회. */
@RestController
@RequiredArgsConstructor
public class SubmissionAttemptController {
    private final SubmissionHistoryReader history;
    private final SubmissionTestResultService tests;

    /**
     * 본인 일반 제출의 보존된 채점 회차를 조회한다. (API 명세서 §3.1.6)
     *
     * @param user Gateway에서 인증한 회원
     * @param submissionId 조회할 제출 식별자
     * @return 회차별 상태와 완료 결과 목록
     */
    @GetMapping("/api/v1/submissions/{submissionId}/judge-attempts")
    public ResponseEntity<ApiResponse<SubmissionAttemptsResponse>> attempts(
        @AuthenticationPrincipal GatewayUser user,
        @PathVariable String submissionId
    ) {
        return ResponseEntity
            .ok(
                ApiResponse
                    .success(
                        SubmissionAttemptsResponse
                            .from(
                                history
                                    .read(
                                        SubmissionCreateRequest.parseId(user.userId()),
                                        SubmissionCreateRequest.parseId(submissionId)
                                    )
                            )
                    )
            );
    }

    /**
     * 회차별 테스트 결과를 공개 범위에 맞게 조회한다. (API 명세서 §3.1.7)
     *
     * @param user Gateway에서 인증한 회원
     * @param submissionId 조회할 제출 식별자
     * @param judgeAttempt 조회할 채점 회차
     * @return 숨김 테스트의 번호·통과 여부만 포함하는 테스트 결과 목록
     */
    @GetMapping("/api/v1/submissions/{submissionId}/judge-attempts/{judgeAttempt}/test-results")
    public ResponseEntity<ApiResponse<SubmissionTestResultsResponse>> results(
        @AuthenticationPrincipal GatewayUser user,
        @PathVariable String submissionId,
        @Min(
            value = 1,
            message = "채점 회차는 1 이상이어야 합니다."
        ) @PathVariable int judgeAttempt
    ) {
        return ResponseEntity
            .ok(
                ApiResponse
                    .success(
                        tests
                            .read(
                                SubmissionCreateRequest.parseId(user.userId()),
                                SubmissionCreateRequest.parseId(submissionId),
                                judgeAttempt
                            )
                    )
            );
    }
}
