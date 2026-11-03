package com.backtoback.integration.github.worker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.integration.github.domain.GithubConnection;
import com.backtoback.integration.github.domain.GithubSyncJob;
import com.backtoback.integration.github.domain.GithubSyncJobStatus;
import com.backtoback.integration.github.repository.GithubSyncJobRepository;
import com.backtoback.integration.github.service.GithubSyncExecutionService;

@ExtendWith(MockitoExtension.class)
class GithubSyncJobWorkerTest {

    @Mock
    private GithubSyncJobRepository syncJobRepository;

    @Mock
    private GithubSyncExecutionService executionService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private TransactionStatus transactionStatus;

    private Clock fixedClock;
    private GithubSyncJobWorker worker;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneId.of("Asia/Seoul"));

        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        });

        worker = new GithubSyncJobWorker(syncJobRepository, executionService, transactionTemplate, fixedClock);
    }

    private GithubSyncJob createPendingJob(Long id) {
        GithubConnection connection
            = GithubConnection
                .builder()
                .memberId(1L)
                .installationId(123L)
                .encryptedToken(
                    new byte[] {
                        1
                    }
                )
                .githubLogin("tester")
                .repositoryOwner("tester")
                .repositoryName("repo")
                .autoCommitEnabled(true)
                .commitPathRule("{난이도}/{문제번호}-{문제명}/풀이.java")
                .build();
        ReflectionTestUtils.setField(connection, "id", 10L);

        GithubSyncJob job = GithubSyncJob.createPending(connection, 100L, 200L, "문제", 1);
        ReflectionTestUtils.setField(job, "id", id);
        return job;
    }

    @Test
    @DisplayName("선점 대상 작업이 있으면 RUNNING으로 변경하고 executionService를 각각 호출한다")
    void pollAndExecuteSyncJobsSuccess() {
        GithubSyncJob job1 = createPendingJob(1L);
        GithubSyncJob job2 = createPendingJob(2L);

        given(syncJobRepository.findNextClaimableJobForUpdateSkipLocked(any(LocalDateTime.class)))
            .willReturn(Optional.of(job1), Optional.of(job2), Optional.empty());

        worker.pollAndExecuteSyncJobs();

        assertThat(job1.getStatus()).isEqualTo(GithubSyncJobStatus.RUNNING);
        assertThat(job2.getStatus()).isEqualTo(GithubSyncJobStatus.RUNNING);
        verify(executionService).executeSyncJob(1L);
        verify(executionService).executeSyncJob(2L);
    }

    @Test
    @DisplayName("선점 대상 작업이 없으면 executionService를 호출하지 않는다")
    void pollAndExecuteSyncJobsEmpty() {
        given(syncJobRepository.findNextClaimableJobForUpdateSkipLocked(any(LocalDateTime.class)))
            .willReturn(Optional.empty());

        worker.pollAndExecuteSyncJobs();

        verify(executionService, never()).executeSyncJob(any());
    }

    @Test
    @DisplayName("특정 작업 실행 중 예외가 발생해도 다음 작업을 이어서 실행한다")
    void pollAndExecuteSyncJobsHandlesException() {
        GithubSyncJob job1 = createPendingJob(1L);
        GithubSyncJob job2 = createPendingJob(2L);
        given(syncJobRepository.findNextClaimableJobForUpdateSkipLocked(any(LocalDateTime.class)))
            .willReturn(Optional.of(job1), Optional.of(job2), Optional.empty());

        doThrow(new RuntimeException("Execution error")).when(executionService).executeSyncJob(1L);

        worker.pollAndExecuteSyncJobs();

        verify(executionService).executeSyncJob(1L);
        verify(executionService).executeSyncJob(2L);
    }
}
