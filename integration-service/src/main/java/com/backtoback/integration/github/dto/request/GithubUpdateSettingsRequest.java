package com.backtoback.integration.github.dto.request;

import jakarta.validation.constraints.NotNull;

/**
 * GitHub 연동 설정 변경 요청 DTO (api-spec.md > 8.1.4).
 */
public record GithubUpdateSettingsRequest(
    @NotNull(message = "isAutoCommitEnabled는 필수입니다.") Boolean isAutoCommitEnabled
) {
}
