package com.backtoback.study.hint.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.study.hint.dto.HintAcceptResult;
import com.backtoback.study.hint.dto.HintDetailResponse;
import com.backtoback.study.hint.dto.HintHistoryResponse;
import com.backtoback.study.hint.dto.HintRequestBody;
import com.backtoback.study.hint.dto.HintRequestResponse;
import com.backtoback.study.hint.service.HintDailyLimitService;
import com.backtoback.study.hint.service.HintQueryService;
import com.backtoback.study.hint.service.HintRequestService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * 힌트 요청 API (api-spec §6.1.1 ~ §6.1.3).
 */
@RestController
@RequestMapping("/api/v1/hint-requests")
@RequiredArgsConstructor
public class HintController {

    private final HintRequestService hintRequestService;
    private final HintQueryService hintQueryService;

    @PostMapping
    public ResponseEntity<ApiResponse<HintRequestResponse>> requestHint(
        @Valid @RequestBody HintRequestBody body,
        @AuthenticationPrincipal GatewayUser user
    ) {
        Long memberId = Long.parseLong(user.userId());
        Long problemId = Long.parseLong(body.problemId());

        HintAcceptResult result = hintRequestService.request(memberId, problemId, body.level());

        return ResponseEntity
            .status(HttpStatus.ACCEPTED)
            .body(
                ApiResponse
                    .success(
                        "힌트 생성을 요청했습니다.",
                        new HintRequestResponse(
                            String.valueOf(result.hintRequestId()),
                            result.status().name(),
                            result.content(),
                            result.todayUsedCount(),
                            HintDailyLimitService.DAILY_LIMIT
                        )
                    )
            );
    }

    /**
     * 6.1.2 힌트 이력 조회
     */
    @GetMapping
    public ResponseEntity<ApiResponse<HintHistoryResponse>> getHintHistory(
        @RequestParam String problemId,
        @AuthenticationPrincipal GatewayUser user
    ) {
        Long memberId = Long.parseLong(user.userId());

        HintHistoryResponse response = hintQueryService.getHistory(memberId, Long.parseLong(problemId));

        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 6.1.3 힌트 단건 조회
     */
    @GetMapping("/{hintRequestId}")
    public ResponseEntity<ApiResponse<HintDetailResponse>> getHintDetail(
        @PathVariable String hintRequestId,
        @AuthenticationPrincipal GatewayUser user
    ) {
        Long memberId = Long.parseLong(user.userId());

        HintDetailResponse response = hintQueryService.getDetail(memberId, Long.parseLong(hintRequestId));

        return ResponseEntity.ok(ApiResponse.success(response));
    }
}
