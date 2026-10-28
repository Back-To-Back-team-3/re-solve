package com.backtoback.integration.github.dto.response;

import java.time.LocalDateTime;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * GitHub 연동 상태 조회 응답 DTO (api-spec.md > 8.1.1).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record GithubConnectionInfoResponse(
    String status,
    String githubLogin,
    String repositoryOwner,
    String repositoryName,
    Boolean isAutoCommitEnabled,
    LocalDateTime connectedAt
) {

    public static GithubConnectionInfoResponse notConnected() {
        return new GithubConnectionInfoResponse("NOT_CONNECTED", null, null, null, null, null);
    }

    public static GithubConnectionInfoResponse connected(
        String githubLogin,
        String repositoryOwner,
        String repositoryName,
        boolean isAutoCommitEnabled,
        LocalDateTime connectedAt
    ) {
        return new GithubConnectionInfoResponse(
            "CONNECTED",
            githubLogin,
            repositoryOwner,
            repositoryName,
            isAutoCommitEnabled,
            connectedAt
        );
    }
}
