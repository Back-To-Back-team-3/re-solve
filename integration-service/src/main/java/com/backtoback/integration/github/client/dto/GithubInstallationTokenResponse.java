package com.backtoback.integration.github.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GitHub App 설치 Access Token 응답 (POST /app/installations/{installation_id}/access_tokens).
 */
public record GithubInstallationTokenResponse(String token, @JsonProperty("expires_at") String expiresAt) {
}
