package com.backtoback.integration.github.client.dto;

/**
 * GitHub App 설치 정보 응답 (GET /app/installations/{installation_id}).
 */
public record GithubInstallationResponse(Long id, Account account) {

    public record Account(Long id, String login, String type) {
    }
}
