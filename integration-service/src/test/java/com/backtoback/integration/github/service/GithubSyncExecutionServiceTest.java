package com.backtoback.integration.github.service;

import static org.assertj.core.api.Assertions.assertThat;
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
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import java.util.function.Consumer;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.integration.github.client.GithubApiException;
import com.backtoback.integration.github.client.GithubAppClient;
import com.backtoback.integration.github.client.JudgeServiceClient;
import com.backtoback.integration.github.client.dto.AcceptedSubmissionCodeResponse;
import com.backtoback.integration.github.client.dto.GithubCommitResultResponse;
import com.backtoback.integration.github.client.dto.GithubInstallationTokenResponse;
import com.backtoback.integration.github.domain.GithubConnection;
import com.backtoback.integration.github.domain.GithubConnectionStatus;
import com.backtoback.integration.github.domain.GithubDisconnectedReason;
import com.backtoback.integration.github.domain.GithubSyncJob;
import com.backtoback.integration.github.repository.GithubConnectionRepository;
import com.backtoback.integration.github.repository.GithubSyncJobRepository;
import com.backtoback.integration.infra.event.domain.OutboxEvent;
import com.backtoback.integration.infra.event.repository.OutboxEventRepository;

import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class GithubSyncExecutionServiceTest {

    @Mock
    private GithubSyncJobRepository syncJobRepository;

    @Mock
    private GithubConnectionRepository connectionRepository;

    @Mock
    private GithubAppClient githubAppClient;

    @Mock
    private JudgeServiceClient judgeServiceClient;

    @Mock
    private GithubSyncJobService syncJobService;

    @Mock
    private OutboxEventRepository outboxEventRepository;

    @Mock
    private TransactionTemplate transactionTemplate;

    @Mock
    private TransactionStatus transactionStatus;

    private GithubCommitPathResolver pathResolver;
    private ObjectMapper objectMapper;
    private Clock fixedClock;
    private GithubSyncExecutionService executionService;

    @BeforeEach
    void setUp() {
        pathResolver = new GithubCommitPathResolver();
        objectMapper = new ObjectMapper();
        fixedClock = Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneId.of("Asia/Seoul"));

        // TransactionTemplate의 execute 및 executeWithoutResult를 동기 실행으로 설정
        lenient().when(transactionTemplate.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(transactionStatus);
        });
        lenient().doAnswer(invocation -> {
            Consumer<TransactionStatus> action = invocation.getArgument(0);
            action.accept(transactionStatus);
            return null;
        }).when(transactionTemplate).executeWithoutResult(any());

        executionService
            = new GithubSyncExecutionService(
                syncJobRepository,
                connectionRepository,
                githubAppClient,
                judgeServiceClient,
                pathResolver,
                syncJobService,
                outboxEventRepository,
                objectMapper,
                fixedClock,
                transactionTemplate
            );
    }

    private GithubConnection createTestConnection() {
        GithubConnection connection
            = GithubConnection
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
                .githubLogin("kim-dev")
                .repositoryOwner("kim-dev")
                .repositoryName("algorithm-solutions")
                .autoCommitEnabled(true)
                .commitPathRule("{난이도}/{문제번호}-{문제명}/풀이.java")
                .build();
        ReflectionTestUtils.setField(connection, "id", 10L);
        return connection;
    }

    private GithubSyncJob createRunningJob(GithubConnection connection) {
        GithubSyncJob job = GithubSyncJob.createPending(connection, 100L, 200L, "두 수의 합", 1);
        job.markRunning();
        ReflectionTestUtils.setField(job, "id", 1L);
        ReflectionTestUtils.setField(job, "updatedAt", LocalDateTime.now(fixedClock));
        return job;
    }

    @Test
    @DisplayName("정상 실행 시 Contents API로 커밋하고 updateStatusToSucceeded 조건부 쿼리를 호출한다")
    void executeSyncJobSuccess() {
        GithubConnection connection = createTestConnection();
        GithubSyncJob job = createRunningJob(connection);
        given(syncJobRepository.findByIdWithConnection(1L)).willReturn(Optional.of(job));
        given(
            syncJobRepository
                .updateStatusToSucceeded(
                    eq(1L),
                    any(LocalDateTime.class),
                    eq("commit-sha-abc"),
                    any(LocalDateTime.class)
                )
        ).willReturn(1);

        given(githubAppClient.createInstallationAccessToken(12345L))
            .willReturn(new GithubInstallationTokenResponse("ghs_token", "2026-10-29T11:00:00Z"));

        AcceptedSubmissionCodeResponse codeResponse
            = new AcceptedSubmissionCodeResponse("100", "200", "python3", "print(1)", 1, OffsetDateTime.now());
        given(judgeServiceClient.getAcceptedCode(100L, 1L)).willReturn(codeResponse);

        given(githubAppClient.findContentSha(eq("ghs_token"), eq("kim-dev"), eq("algorithm-solutions"), anyString()))
            .willReturn(Optional.empty());

        given(
            githubAppClient
                .putContent(
                    eq("ghs_token"),
                    eq("kim-dev"),
                    eq("algorithm-solutions"),
                    anyString(),
                    anyString(),
                    eq("print(1)"),
                    eq(null)
                )
        ).willReturn(new GithubCommitResultResponse(new GithubCommitResultResponse.Commit("commit-sha-abc")));

        executionService.executeSyncJob(1L);

        verify(syncJobRepository)
            .updateStatusToSucceeded(eq(1L), any(LocalDateTime.class), eq("commit-sha-abc"), any(LocalDateTime.class));
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("401 권한 만료 시 연동을 AUTH_EXPIRED로 해제하고 작업을 FAILED로 종료하며 Outbox 이벤트를 발행한다")
    void executeSyncJobAuthExpired() {
        GithubConnection connection = createTestConnection();
        GithubSyncJob job = createRunningJob(connection);
        given(syncJobRepository.findByIdWithConnection(1L)).willReturn(Optional.of(job));
        given(connectionRepository.findById(connection.getId())).willReturn(Optional.of(connection));
        given(
            syncJobRepository
                .updateStatusToFailed(eq(1L), any(LocalDateTime.class), anyString(), any(LocalDateTime.class))
        ).willReturn(1);

        given(githubAppClient.createInstallationAccessToken(12345L))
            .willThrow(new GithubApiException("Unauthorized", 401, null, null));

        executionService.executeSyncJob(1L);

        assertThat(connection.getStatus()).isEqualTo(GithubConnectionStatus.DISCONNECTED);
        assertThat(connection.getDisconnectedReason()).isEqualTo(GithubDisconnectedReason.AUTH_EXPIRED);
        verify(syncJobService).cancelPendingJobs(eq(connection.getId()), anyString());
        verify(syncJobRepository)
            .updateStatusToFailed(eq(1L), any(LocalDateTime.class), anyString(), any(LocalDateTime.class));

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        OutboxEvent outboxEvent = outboxCaptor.getValue();
        assertThat(outboxEvent.getEventType()).isEqualTo("GitHubSyncFailed");
        assertThat(outboxEvent.getPayload()).contains("AUTH_EXPIRED");
    }

    @Test
    @DisplayName("403 Rate Limit 발생 시 재시도 횟수 5회 미만이면 updateStatusToRateLimited 조건부 쿼리를 호출한다")
    void executeSyncJobRateLimited() {
        GithubConnection connection = createTestConnection();
        GithubSyncJob job = createRunningJob(connection);
        given(syncJobRepository.findByIdWithConnection(1L)).willReturn(Optional.of(job));
        given(
            syncJobRepository
                .updateStatusToRateLimited(
                    eq(1L),
                    any(LocalDateTime.class),
                    any(LocalDateTime.class),
                    anyString(),
                    any(LocalDateTime.class)
                )
        ).willReturn(1);

        Instant resetInstant = Instant.parse("2026-10-29T10:30:00Z");
        given(githubAppClient.createInstallationAccessToken(12345L))
            .willThrow(new GithubApiException("Rate Limit", 403, resetInstant, null));

        executionService.executeSyncJob(1L);

        verify(syncJobRepository)
            .updateStatusToRateLimited(
                eq(1L),
                any(LocalDateTime.class),
                eq(LocalDateTime.ofInstant(resetInstant, ZoneId.of("Asia/Seoul"))),
                anyString(),
                any(LocalDateTime.class)
            );
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("403 Rate Limit 발생 시 재시도 횟수가 5회 이상이면 FAILED로 전이하고 RATE_LIMIT_EXHAUSTED Outbox 이벤트를 발행한다")
    void executeSyncJobRateLimitExceedMaxRetriesFails() {
        GithubConnection connection = createTestConnection();
        GithubSyncJob job = createRunningJob(connection);
        for (int i = 0; i < 5; i++) {
            job.markRetryWaiting(null, "이전 실패");
            job.markRunning();
        }
        assertThat(job.getRetryCount()).isEqualTo(5);

        given(syncJobRepository.findByIdWithConnection(1L)).willReturn(Optional.of(job));
        given(
            syncJobRepository
                .updateStatusToFailed(eq(1L), any(LocalDateTime.class), anyString(), any(LocalDateTime.class))
        ).willReturn(1);

        Instant resetInstant = Instant.parse("2026-10-29T10:30:00Z");
        given(githubAppClient.createInstallationAccessToken(12345L))
            .willThrow(new GithubApiException("Rate Limit", 403, resetInstant, null));

        executionService.executeSyncJob(1L);

        verify(syncJobRepository)
            .updateStatusToFailed(eq(1L), any(LocalDateTime.class), anyString(), any(LocalDateTime.class));

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxEventRepository).save(outboxCaptor.capture());
        OutboxEvent outboxEvent = outboxCaptor.getValue();
        assertThat(outboxEvent.getEventType()).isEqualTo("GitHubSyncFailed");
        assertThat(outboxEvent.getPayload()).contains("RATE_LIMIT_EXHAUSTED");
    }

    @Test
    @DisplayName("일시 오류 시 재시도 횟수가 5회 이하이면 updateStatusToRetryWaiting을 호출한다")
    void executeSyncJobRetryWaiting() {
        GithubConnection connection = createTestConnection();
        GithubSyncJob job = createRunningJob(connection);
        given(syncJobRepository.findByIdWithConnection(1L)).willReturn(Optional.of(job));
        given(
            syncJobRepository
                .updateStatusToRetryWaiting(
                    eq(1L),
                    any(LocalDateTime.class),
                    any(LocalDateTime.class),
                    anyString(),
                    any(LocalDateTime.class)
                )
        ).willReturn(1);

        given(githubAppClient.createInstallationAccessToken(12345L))
            .willThrow(new GithubApiException("Server Error", 500, null, null));

        executionService.executeSyncJob(1L);

        verify(syncJobRepository)
            .updateStatusToRetryWaiting(
                eq(1L),
                any(LocalDateTime.class),
                any(LocalDateTime.class),
                anyString(),
                any(LocalDateTime.class)
            );
        verify(outboxEventRepository, never()).save(any());
    }

    @Test
    @DisplayName("재시도 상한(5회) 초과 시 updateStatusToFailed 호출 및 GitHubSyncFailed Outbox 이벤트를 발행한다")
    void executeSyncJobExceedMaxRetriesFails() {
        GithubConnection connection = createTestConnection();
        GithubSyncJob job = createRunningJob(connection);
        for (int i = 0; i < 5; i++) {
            job.markRetryWaiting(null, "이전 실패");
            job.markRunning();
        }
        assertThat(job.getRetryCount()).isEqualTo(5);

        given(syncJobRepository.findByIdWithConnection(1L)).willReturn(Optional.of(job));
        given(
            syncJobRepository
                .updateStatusToFailed(eq(1L), any(LocalDateTime.class), anyString(), any(LocalDateTime.class))
        ).willReturn(1);
        given(githubAppClient.createInstallationAccessToken(12345L))
            .willThrow(new GithubApiException("Server Error", 500, null, null));

        executionService.executeSyncJob(1L);

        verify(syncJobRepository)
            .updateStatusToFailed(eq(1L), any(LocalDateTime.class), anyString(), any(LocalDateTime.class));
        verify(outboxEventRepository).save(any(OutboxEvent.class));
    }
}
