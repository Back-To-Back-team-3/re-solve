package com.backtoback.integration.github.dto.request;

import jakarta.validation.constraints.NotBlank;

/**
 * GitHub App 설치 완료 요청 DTO (api-spec.md > 8.1.3).
 */
public record GithubInstallRequest(
    @NotBlank(message = "installationId는 필수입니다.") String installationId,

    @NotBlank(message = "state는 필수입니다.") String state
) {
}
