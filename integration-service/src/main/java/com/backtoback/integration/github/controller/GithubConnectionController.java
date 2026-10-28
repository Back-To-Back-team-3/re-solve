package com.backtoback.integration.github.controller;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.backtoback.common.api.ApiResponse;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.integration.github.dto.request.GithubInstallRequest;
import com.backtoback.integration.github.dto.request.GithubUpdateSettingsRequest;
import com.backtoback.integration.github.dto.response.GithubConnectionInfoResponse;
import com.backtoback.integration.github.dto.response.GithubInstallResultResponse;
import com.backtoback.integration.github.dto.response.GithubInstallUrlResponse;
import com.backtoback.integration.github.dto.response.GithubSettingsResultResponse;
import com.backtoback.integration.github.service.GithubConnectionService;
import com.backtoback.integration.global.error.IntegrationException;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;

/**
 * GitHub App 연동 REST API 컨트롤러 (api-spec.md > 8.1.1 ~ 8.1.5).
 */
@RestController
@RequestMapping("/api/v1/integrations/github")
@RequiredArgsConstructor
public class GithubConnectionController {

    private final GithubConnectionService githubConnectionService;

    /**
     * 8.1.1 GitHub 연동 상태 조회.
     *
     * @param memberId Gateway가 전달한 사용자 ID
     * @return 연동 상태 정보
     */
    @GetMapping
    public ResponseEntity<ApiResponse<GithubConnectionInfoResponse>> getConnection(
        @RequestHeader("X-User-Id") Long memberId
    ) {
        GithubConnectionInfoResponse response = githubConnectionService.getConnection(memberId);
        return ResponseEntity.ok(ApiResponse.success(response));
    }

    /**
     * 8.1.2 GitHub App 설치 주소 조회.
     *
     * @param memberId Gateway가 전달한 사용자 ID
     * @return 설치 URL과 state가 포함된 주소 응답
     */
    @GetMapping("/install-url")
    public ResponseEntity<ApiResponse<GithubInstallUrlResponse>> getInstallUrl(
        @RequestHeader("X-User-Id") Long memberId
    ) {
        String installUrl = githubConnectionService.getInstallUrl(memberId);
        return ResponseEntity.ok(ApiResponse.success(new GithubInstallUrlResponse(installUrl)));
    }

    /**
     * 8.1.3 GitHub App 설치 완료.
     *
     * @param memberId Gateway가 전달한 사용자 ID
     * @param role Gateway가 전달한 사용자 역할
     * @param request 설치 ID 및 state 요청 바디
     * @return 연동 완료 결과
     */
    @PostMapping("/install")
    public ResponseEntity<ApiResponse<GithubInstallResultResponse>> install(
        @RequestHeader("X-User-Id") Long memberId,
        @RequestHeader(
            value = "X-User-Role",
            required = false
        ) String role,
        @Valid @RequestBody GithubInstallRequest request
    ) {
        if ("SUSPENDED".equalsIgnoreCase(role)) {
            throw new IntegrationException(CommonErrorCode.AUTH_ACCESS_DENIED);
        }
        GithubInstallResultResponse response
            = githubConnectionService.install(memberId, request.installationId(), request.state());
        return ResponseEntity.ok(ApiResponse.success("GitHub 저장소 연동이 완료되었습니다.", response));
    }

    /**
     * 8.1.4 연동 설정 변경.
     *
     * @param memberId Gateway가 전달한 사용자 ID
     * @param request 자동 커밋 활성화 여부
     * @return 변경된 설정 결과
     */
    @PatchMapping
    public ResponseEntity<ApiResponse<GithubSettingsResultResponse>> updateSettings(
        @RequestHeader("X-User-Id") Long memberId,
        @Valid @RequestBody GithubUpdateSettingsRequest request
    ) {
        GithubSettingsResultResponse response
            = githubConnectionService.updateSettings(memberId, request.isAutoCommitEnabled());
        return ResponseEntity.ok(ApiResponse.success("연동 설정이 변경되었습니다.", response));
    }

    /**
     * 8.1.5 GitHub 연동 해제.
     *
     * @param memberId Gateway가 전달한 사용자 ID
     * @return 204 No Content
     */
    @DeleteMapping
    public ResponseEntity<Void> disconnect(@RequestHeader("X-User-Id") Long memberId) {
        githubConnectionService.disconnect(memberId);
        return ResponseEntity.noContent().build();
    }
}
