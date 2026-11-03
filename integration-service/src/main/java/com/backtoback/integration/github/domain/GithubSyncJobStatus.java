package com.backtoback.integration.github.domain;

/**
 * GitHub 자동 커밋 작업 상태 (erd.md > 4.8.2, scheduler.md > 8.1).
 */
public enum GithubSyncJobStatus {
    PENDING, RUNNING, RETRY_WAITING, RATE_LIMITED, SUCCEEDED, FAILED, CANCELED
}
