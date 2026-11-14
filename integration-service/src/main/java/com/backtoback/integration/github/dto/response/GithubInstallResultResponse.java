package com.backtoback.integration.github.dto.response;

/**
 * GitHub App 설치 완료 응답 DTO (api-spec.md > 8.1.3).
 */
public record GithubInstallResultResponse(String status, String repositoryName, boolean isAutoCommitEnabled) {
}
