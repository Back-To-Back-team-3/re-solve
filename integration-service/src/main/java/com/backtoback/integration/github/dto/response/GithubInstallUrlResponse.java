package com.backtoback.integration.github.dto.response;

/**
 * GitHub App 설치 URL 조회 응답 DTO (api-spec.md > 8.1.2).
 */
public record GithubInstallUrlResponse(String installUrl) {
}
