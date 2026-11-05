package com.backtoback.integration.github.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.api.PageResponse;
import com.backtoback.integration.github.dto.request.GithubManualSyncRequest;
import com.backtoback.integration.github.dto.response.GithubManualSyncResponse;
import com.backtoback.integration.github.dto.response.GithubSyncJobItemResponse;
import com.backtoback.integration.github.service.GithubSyncJobService;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * GitHub 자동 커밋 동기화 작업 조회 및 수동 동기화 요청 REST API 컨트롤러 (api-spec.md > 8.1.6, 8.1.7).
 */
@RestController
@RequestMapping("/api/v1/integrations/github/sync-jobs")
@RequiredArgsConstructor
public class GithubSyncJobController {

    private final GithubSyncJobService githubSyncJobService;

    /**
     * 8.1.6 동기화 작업 목록 조회.
     *
     * @param memberId Gateway가 전달한 사용자 ID
     * @param status Sync Job 상태 필터 (선택)
     * @param page 페이지 번호 (기본 0)
     * @param size 페이지 크기 (기본 20)
     * @param sort 정렬 조건 (기본 createdAt,desc)
     * @return 동기화 작업 페이지 응답
     */
    @GetMapping
    public ResponseEntity<ApiResponse<PageResponse<GithubSyncJobItemResponse>>> getSyncJobs(
        @RequestHeader("X-User-Id") Long memberId,
        @RequestParam(
            value = "status",
            required = false
        ) String status,
        @RequestParam(
            value = "page",
            defaultValue = "0"
        ) int page,
        @RequestParam(
            value = "size",
            defaultValue = "20"
        ) int size,
        @RequestParam(
            value = "sort",
            defaultValue = "createdAt,desc"
        ) String sort
    ) {
        PageResponse<GithubSyncJobItemResponse> response
            = githubSyncJobService.getSyncJobs(memberId, status, page, size, sort);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 8.1.7 수동 동기화 요청.
     *
     * @param memberId Gateway가 전달한 사용자 ID
     * @param request 제출 ID를 포함한 수동 동기화 요청 바디
     * @return 202 Accepted 및 등록된 작업 상태 응답
     */
    @PostMapping
    public ResponseEntity<ApiResponse<GithubManualSyncResponse>> requestManualSync(
        @RequestHeader("X-User-Id") Long memberId,
        @Valid @RequestBody GithubManualSyncRequest request
    ) {
        GithubManualSyncResponse response = githubSyncJobService.requestManualSync(memberId, request.submissionId());
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(ApiResponse.success("GitHub 커밋 작업을 등록했습니다.", response));
    }
}
