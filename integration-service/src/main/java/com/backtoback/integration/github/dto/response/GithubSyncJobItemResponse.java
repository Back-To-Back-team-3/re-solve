package com.backtoback.integration.github.dto.response;

import java.time.OffsetDateTime;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.integration.github.domain.GithubSyncJob;

/**
 * GitHub 자동 커밋 동기화 작업 목록 항목 응답 DTO (api-spec.md > 8.1.6).
 */
public record GithubSyncJobItemResponse(
    String syncJobId,
    String submissionId,
    String problemId,
    String status,
    int retryCount,
    String commitSha,
    String errorMessage,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {

    /**
     * 도메인 엔티티로부터 목록 항목 응답 DTO를 생성한다.
     *
     * @param job GitHub 자동 커밋 작업 엔티티
     * @return 응답 DTO
     */
    public static GithubSyncJobItemResponse from(GithubSyncJob job) {
        return new GithubSyncJobItemResponse(
            String.valueOf(job.getId()),
            String.valueOf(job.getSubmissionId()),
            String.valueOf(job.getProblemId()),
            job.getStatus().name(),
            job.getRetryCount(),
            job.getCommitSha(),
            job.getErrorMessage(),
            job.getCreatedAt() != null ? job.getCreatedAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime()
                : null,
            job.getUpdatedAt() != null ? job.getUpdatedAt().atZone(ClockConfiguration.SERVICE_ZONE).toOffsetDateTime()
                : null
        );
    }
}
