package com.backtoback.integration.github.event.dto;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * GitHub 자동 커밋 최종 실패 도메인 이벤트 페이로드 (event-contract.md > 4.5 I1 GitHubSyncFailed v1).
 */
public record GithubSyncFailedEvent(
    @JsonProperty("syncJobId") String syncJobId,
    @JsonProperty("memberId") String memberId,
    @JsonProperty("submissionId") String submissionId,
    @JsonProperty("problemId") String problemId,
    @JsonProperty("problemTitle") String problemTitle,
    @JsonProperty("errorCode") String errorCode,
    @JsonProperty("failedAt") String failedAt
) {
    public static GithubSyncFailedEvent create(
        Long syncJobId,
        Long memberId,
        Long submissionId,
        Long problemId,
        String problemTitle,
        String errorCode,
        LocalDateTime failedAt
    ) {
        String failedAtStr = failedAt != null ? failedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) : null;
        return new GithubSyncFailedEvent(
            String.valueOf(syncJobId),
            String.valueOf(memberId),
            String.valueOf(submissionId),
            String.valueOf(problemId),
            problemTitle,
            errorCode,
            failedAtStr
        );
    }
}
