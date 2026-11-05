package com.backtoback.integration.github.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * GitHub 수동 동기화 요청 DTO (api-spec.md > 8.1.7).
 */
public record GithubManualSyncRequest(@NotBlank(message = "submissionId는 필수입니다.") String submissionId) {
}
