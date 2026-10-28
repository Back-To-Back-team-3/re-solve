package com.backtoback.integration.github.dto.response;

/**
 * GitHub 연동 설정 변경 응답 DTO (api-spec.md > 8.1.4).
 */
public record GithubSettingsResultResponse(boolean isAutoCommitEnabled) {
}
