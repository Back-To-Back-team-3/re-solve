package com.backtoback.integration.github.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GithubSyncJobTest {

    private GithubConnection createTestConnection() {
        return GithubConnection
            .builder()
            .memberId(1L)
            .installationId(12345L)
            .encryptedToken(
                new byte[] {
                    1,
                    2,
                    3
                }
            )
            .githubLogin("test-user")
            .repositoryOwner("test-user")
            .repositoryName("algorithm-solutions")
            .commitPathRule("{난이도}/{문제번호}-{문제명}/풀이.java")
            .build();
    }

    @Test
    @DisplayName("작업 생성 시 초기 상태는 PENDING이고 재시도 횟수는 0이며 에러 메시지와 재시도 시각은 null이다")
    void createPendingJobInitialState() {
        GithubConnection connection = createTestConnection();
        GithubSyncJob job = GithubSyncJob.createPending(connection, 100L, 200L, "두 수의 합", 1);

        assertThat(job.getConnection()).isEqualTo(connection);
        assertThat(job.getSubmissionId()).isEqualTo(100L);
        assertThat(job.getProblemId()).isEqualTo(200L);
        assertThat(job.getStatus()).isEqualTo(GithubSyncJobStatus.PENDING);
        assertThat(job.getRetryCount()).isEqualTo(0);
        assertThat(job.getNextRetryAt()).isNull();
        assertThat(job.getCommitSha()).isNull();
        assertThat(job.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("필수 정보(connection, submissionId, problemId)가 누락되면 IllegalArgumentException이 발생한다")
    void createPendingValidationFails() {
        GithubConnection connection = createTestConnection();

        assertThatThrownBy(() -> GithubSyncJob.createPending(null, 100L, 200L, "두 수의 합", 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GithubSyncJob.createPending(connection, null, 200L, "두 수의 합", 1))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> GithubSyncJob.createPending(connection, 100L, null, "두 수의 합", 1))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("markRunning 호출 시 상태가 RUNNING으로 변경되고 nextRetryAt은 null이 된다")
    void markRunning() {
        GithubSyncJob job = GithubSyncJob.createPending(createTestConnection(), 100L, 200L, "두 수의 합", 1);
        job.markRunning();

        assertThat(job.getStatus()).isEqualTo(GithubSyncJobStatus.RUNNING);
        assertThat(job.getNextRetryAt()).isNull();
    }

    @Test
    @DisplayName("markSucceeded 호출 시 상태가 SUCCEEDED로 변경되고 커밋 SHA가 저장된다")
    void markSucceeded() {
        GithubSyncJob job = GithubSyncJob.createPending(createTestConnection(), 100L, 200L, "두 수의 합", 1);
        job.markRunning();
        job.markSucceeded("abc1234def5678");

        assertThat(job.getStatus()).isEqualTo(GithubSyncJobStatus.SUCCEEDED);
        assertThat(job.getCommitSha()).isEqualTo("abc1234def5678");
        assertThat(job.getNextRetryAt()).isNull();
        assertThat(job.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("markRateLimited 호출 시 상태가 RATE_LIMITED로 변경되고 재시도 시각이 설정된다")
    void markRateLimited() {
        GithubSyncJob job = GithubSyncJob.createPending(createTestConnection(), 100L, 200L, "두 수의 합", 1);
        job.markRunning();
        LocalDateTime resetAt = LocalDateTime.now().plusMinutes(15);
        job.markRateLimited(resetAt);

        assertThat(job.getStatus()).isEqualTo(GithubSyncJobStatus.RATE_LIMITED);
        assertThat(job.getNextRetryAt()).isEqualTo(resetAt);
        assertThat(job.getErrorMessage()).contains("Rate Limit");
    }

    @Test
    @DisplayName("markRetryWaiting 호출 시 상태가 RETRY_WAITING으로 변경되고 재시도 횟수가 증가하며 500자 초과 에러는 잘린다")
    void markRetryWaiting() {
        GithubSyncJob job = GithubSyncJob.createPending(createTestConnection(), 100L, 200L, "두 수의 합", 1);
        job.markRunning();
        LocalDateTime nextRetryAt = LocalDateTime.now().plusMinutes(1);
        String longMessage = "E".repeat(600);
        job.markRetryWaiting(nextRetryAt, longMessage);

        assertThat(job.getStatus()).isEqualTo(GithubSyncJobStatus.RETRY_WAITING);
        assertThat(job.getRetryCount()).isEqualTo(1);
        assertThat(job.getNextRetryAt()).isEqualTo(nextRetryAt);
        assertThat(job.getErrorMessage()).hasSize(500);
    }

    @Test
    @DisplayName("markFailed 호출 시 상태가 FAILED로 변경된다")
    void markFailed() {
        GithubSyncJob job = GithubSyncJob.createPending(createTestConnection(), 100L, 200L, "두 수의 합", 1);
        job.markRunning();
        job.markFailed("Permanent failure");

        assertThat(job.getStatus()).isEqualTo(GithubSyncJobStatus.FAILED);
        assertThat(job.getNextRetryAt()).isNull();
        assertThat(job.getErrorMessage()).isEqualTo("Permanent failure");
    }

    @Test
    @DisplayName("cancel 호출 시 상태가 CANCELED로 변경된다")
    void cancel() {
        GithubSyncJob job = GithubSyncJob.createPending(createTestConnection(), 100L, 200L, "두 수의 합", 1);
        job.cancel("User disconnected");

        assertThat(job.getStatus()).isEqualTo(GithubSyncJobStatus.CANCELED);
        assertThat(job.getNextRetryAt()).isNull();
        assertThat(job.getErrorMessage()).isEqualTo("User disconnected");
    }
}
