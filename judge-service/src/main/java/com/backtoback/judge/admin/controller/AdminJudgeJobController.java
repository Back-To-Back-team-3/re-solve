package com.backtoback.judge.admin.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.api.PageResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.judge.admin.dto.AdminJudgeJobDetailResponse;
import com.backtoback.judge.admin.dto.AdminJudgeJobResponse;
import com.backtoback.judge.admin.dto.JudgeJobListRequest;
import com.backtoback.judge.admin.dto.JudgeJobRecoveryRequest;
import com.backtoback.judge.admin.dto.JudgeJobRecoveryResponse;
import com.backtoback.judge.admin.service.JudgeJobOperationService;
import com.backtoback.judge.submission.controller.SubmissionQueryId;
import com.backtoback.judge.submission.dto.SubmissionCreateRequest;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.RequiredArgsConstructor;

/** Gateway와 서비스의 ADMIN 검증을 거친 채점 작업 운영 API. */
@RestController
@RequiredArgsConstructor
public class AdminJudgeJobController {
    private final JudgeJobOperationService operations;

    /**
     * 작업 상태·문맥·실패 코드로 목록을 조회한다. (API 명세서 §3.2.1)
     *
     * @param request 검증할 조회 조건
     * @return 작업 상태 페이지
     */
    @GetMapping("/api/v1/admin/judge-jobs")
    public ResponseEntity<ApiResponse<PageResponse<AdminJudgeJobResponse>>> list(
        @Valid @ModelAttribute JudgeJobListRequest request
    ) {
        return ResponseEntity
            .ok(ApiResponse.success(PageResponse.from(operations.list(request).map(AdminJudgeJobResponse::from))));
    }

    /**
     * 작업의 실행 시도와 테스트 상태를 조회한다. (API 명세서 §3.2.2)
     *
     * @param judgeJobId 양수 BIGINT 작업 ID
     * @return 원문을 제외한 작업 상세
     * @throws com.backtoback.judge.global.error.JudgeException 작업이 없으면 SUBMISSION_NOT_FOUND
     */
    @GetMapping("/api/v1/admin/judge-jobs/{judgeJobId}")
    public ResponseEntity<ApiResponse<AdminJudgeJobDetailResponse>> detail(
        @PathVariable @SubmissionQueryId String judgeJobId
    ) {
        return ResponseEntity
            .ok(ApiResponse.success(AdminJudgeJobDetailResponse.from(operations.detail(Long.parseLong(judgeJobId)))));
    }

    /**
     * 종결 전 작업의 복구를 같은 회차에 접수한다. (API 명세서 §3.2.5)
     *
     * @param user 인증한 관리자
     * @param judgeJobId 복구할 작업 ID
     * @param key 최초 응답을 식별할 멱등키
     * @param request 복구 사유
     * @return 최초 복구 요청의 응답
     * @throws com.backtoback.judge.global.error.JudgeException 미존재·복구 불가·멱등키 충돌인 경우
     */
    @PostMapping("/api/v1/admin/judge-jobs/{judgeJobId}/recoveries")
    public ResponseEntity<ApiResponse<JudgeJobRecoveryResponse>> recover(
        @AuthenticationPrincipal GatewayUser user,
        @PathVariable @SubmissionQueryId String judgeJobId,
        @RequestHeader("Idempotency-Key")
        @NotBlank(message = "멱등키가 필요합니다.")
        @Pattern(
            regexp = "[\\x20-\\x7E]{1,100}",
            message = "멱등키는 ASCII 1~100자여야 합니다."
        ) String key,
        @RequestBody @Valid JudgeJobRecoveryRequest request
    ) {
        String state
            = operations
                .recover(
                    Long.parseLong(judgeJobId),
                    SubmissionCreateRequest.parseId(user.userId()),
                    key,
                    request.reason()
                );
        return ResponseEntity
            .accepted()
            .body(ApiResponse.success("복구를 요청했습니다.", JudgeJobRecoveryResponse.from(Long.parseLong(judgeJobId), state)));
    }
}
