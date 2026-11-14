package com.backtoback.integration.github.scheduler;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.integration.github.domain.GithubConnection;
import com.backtoback.integration.github.domain.GithubSyncJob;
import com.backtoback.integration.github.domain.GithubSyncJobStatus;
import com.backtoback.integration.github.repository.GithubSyncJobRepository;
import com.backtoback.integration.github.service.GithubSyncExecutionService;

@ExtendWith(MockitoExtension.class)
class GithubSyncStuckJobRecoverySchedulerTest {

    @Mock
    private GithubSyncJobRepository syncJobRepository;

    @Mock
    private GithubSyncExecutionService executionService;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private TransactionStatus transactionStatus;

    private Clock fixedClock;
    private GithubSyncStuckJobRecoveryScheduler recoveryScheduler;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneId.of("Asia/Seoul"));

        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(transactionStatus);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        recoveryScheduler
            = new GithubSyncStuckJobRecoveryScheduler(
                syncJobRepository,
                executionService,
                transactionTemplate,
                fixedClock
            );
    }

    private GithubSyncJob createRunningJob(Long id, int retryCount, LocalDateTime updatedAt) {
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
        job.markRunning();
        ReflectionTestUtils.setField(job, "id", id);
        ReflectionTestUtils.setField(job, "retryCount", retryCount);
        ReflectionTestUtils.setField(job, "updatedAt", updatedAt);
        return job;
    }

    @Test
    @DisplayName("5분 이상 정체된 RUNNING 작업 중 재시도 횟수가 5회 미만이면 RETRY_WAITING으로 복구한다")
    void recoverStuckJobsToRetryWaiting() {
        LocalDateTime observedTime = LocalDateTime.parse("2026-10-29T09:50:00");
        GithubSyncJob stuckJob = createRunningJob(1L, 2, observedTime);

        given(
            syncJobRepository
                .findByStatusAndUpdatedAtBeforeWithConnection(eq(GithubSyncJobStatus.RUNNING), any(LocalDateTime.class))
        ).willReturn(List.of(stuckJob));
        given(
            syncJobRepository
                .recoverStuckJobToRetryWaiting(
                    eq(1L),
                    eq(observedTime),
                    any(LocalDateTime.class),
                    anyString(),
                    any(LocalDateTime.class)
                )
        ).willReturn(1);

        recoveryScheduler.recoverStuckJobs();

        verify(syncJobRepository)
            .recoverStuckJobToRetryWaiting(
                eq(1L),
                eq(observedTime),
                eq(LocalDateTime.now(fixedClock).plusMinutes(1)),
                anyString(),
                any(LocalDateTime.class)
            );
        verify(executionService, never()).publishSyncFailedOutboxEvent(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    @DisplayName("5분 이상 정체된 RUNNING 작업 중 재시도 횟수가 5회 이상이면 FAILED로 복구하고 Outbox 이벤트를 발행한다")
    void recoverStuckJobsExceedMaxRetriesToFailed() {
        LocalDateTime observedTime = LocalDateTime.parse("2026-10-29T09:50:00");
        GithubSyncJob stuckJob = createRunningJob(1L, 5, observedTime);

        given(
            syncJobRepository
                .findByStatusAndUpdatedAtBeforeWithConnection(eq(GithubSyncJobStatus.RUNNING), any(LocalDateTime.class))
        ).willReturn(List.of(stuckJob));
        given(
            syncJobRepository.recoverStuckJobToFailed(eq(1L), eq(observedTime), anyString(), any(LocalDateTime.class))
        ).willReturn(1);

        recoveryScheduler.recoverStuckJobs();

        verify(syncJobRepository)
            .recoverStuckJobToFailed(eq(1L), eq(observedTime), anyString(), any(LocalDateTime.class));
        verify(executionService)
            .publishSyncFailedOutboxEvent(
                eq(1L),
                eq(1L),
                eq(100L),
                eq(200L),
                eq("문제"),
                eq("STUCK_TIMEOUT"),
                any(LocalDateTime.class)
            );
    }

    @Test
    @DisplayName("정체 작업이 없으면 아무 작업도 수행하지 않는다")
    void recoverStuckJobsEmpty() {
        given(
            syncJobRepository
                .findByStatusAndUpdatedAtBeforeWithConnection(eq(GithubSyncJobStatus.RUNNING), any(LocalDateTime.class))
        ).willReturn(Collections.emptyList());

        recoveryScheduler.recoverStuckJobs();

        verify(syncJobRepository, never()).recoverStuckJobToRetryWaiting(any(), any(), any(), any(), any());
        verify(syncJobRepository, never()).recoverStuckJobToFailed(any(), any(), any(), any());
    }
}
