package com.backtoback.integration.github.dto.response;

import com.backtoback.integration.github.domain.GithubSyncJob;

/**
 * GitHub 수동 동기화 요청 결과 응답 DTO (api-spec.md > 8.1.7).
 */
public record GithubManualSyncResponse(String syncJobId, String submissionId, String status) {

    /**
     * 도메인 엔티티로부터 수동 동기화 응답 DTO를 생성한다.
     *
     * @param job GitHub 자동 커밋 작업 엔티티
     * @return 응답 DTO
     */
    public static GithubManualSyncResponse from(GithubSyncJob job) {
        return new GithubManualSyncResponse(
            String.valueOf(job.getId()),
            String.valueOf(job.getSubmissionId()),
            job.getStatus().name()
        );
    }
}
