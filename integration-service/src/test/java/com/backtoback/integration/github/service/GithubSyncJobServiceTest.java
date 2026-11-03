package com.backtoback.integration.github.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.integration.github.domain.GithubConnection;
import com.backtoback.integration.github.domain.GithubDisconnectedReason;
import com.backtoback.integration.github.domain.GithubSyncJob;
import com.backtoback.integration.github.domain.GithubSyncJobStatus;
import com.backtoback.integration.github.repository.GithubConnectionRepository;
import com.backtoback.integration.github.repository.GithubSyncJobRepository;

@ExtendWith(MockitoExtension.class)
class GithubSyncJobServiceTest {

    @Mock
    private GithubConnectionRepository connectionRepository;

    @Mock
    private GithubSyncJobRepository syncJobRepository;

    private Clock fixedClock;
    private GithubSyncJobService syncJobService;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneId.of("Asia/Seoul"));
        syncJobService = new GithubSyncJobService(connectionRepository, syncJobRepository, fixedClock);
    }

    private GithubConnection createConnection(boolean autoCommitEnabled) {
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
                .autoCommitEnabled(autoCommitEnabled)
                .commitPathRule("{난이도}/{문제번호}-{문제명}/풀이.java")
                .build();
        ReflectionTestUtils.setField(connection, "id", 10L);
        return connection;
    }

    @Test
    @DisplayName("연동 정보가 없으면 Sync Job 생성을 생략한다")
    void registerSyncJobWhenConnectionNotFound() {
        given(connectionRepository.findByMemberId(1L)).willReturn(Optional.empty());

        Optional<GithubSyncJob> result = syncJobService.registerSyncJob(1L, 100L, 200L, "두 수의 합", 1);

        assertThat(result).isEmpty();
        verify(syncJobRepository, never()).insertPendingJobIfAbsent(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("연동 상태가 DISCONNECTED이면 Sync Job 생성을 생략한다")
    void registerSyncJobWhenDisconnected() {
        GithubConnection connection = createConnection(true);
        connection.disconnect(GithubDisconnectedReason.USER_REQUEST);
        given(connectionRepository.findByMemberId(1L)).willReturn(Optional.of(connection));

        Optional<GithubSyncJob> result = syncJobService.registerSyncJob(1L, 100L, 200L, "두 수의 합", 1);

        assertThat(result).isEmpty();
        verify(syncJobRepository, never()).insertPendingJobIfAbsent(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("자동 커밋 설정이 비활성화되어 있으면 Sync Job 생성을 생략한다")
    void registerSyncJobWhenAutoCommitDisabled() {
        GithubConnection connection = createConnection(false);
        given(connectionRepository.findByMemberId(1L)).willReturn(Optional.of(connection));

        Optional<GithubSyncJob> result = syncJobService.registerSyncJob(1L, 100L, 200L, "두 수의 합", 1);

        assertThat(result).isEmpty();
        verify(syncJobRepository, never()).insertPendingJobIfAbsent(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("이미 등록된 submissionId가 존재하면 생성을 생략한다")
    void registerSyncJobWhenAlreadyExists() {
        GithubConnection connection = createConnection(true);
        given(connectionRepository.findByMemberId(1L)).willReturn(Optional.of(connection));
        given(syncJobRepository.existsBySubmissionId(100L)).willReturn(true);

        Optional<GithubSyncJob> result = syncJobService.registerSyncJob(1L, 100L, 200L, "두 수의 합", 1);

        assertThat(result).isEmpty();
        verify(syncJobRepository, never()).insertPendingJobIfAbsent(any(), any(), any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("정상 조건이면 insertPendingJobIfAbsent를 호출하고 생성된 작업을 반환한다")
    void registerSyncJobSuccess() {
        GithubConnection connection = createConnection(true);
        GithubSyncJob expectedJob = GithubSyncJob.createPending(connection, 100L, 200L, "두 수의 합", 1);
        ReflectionTestUtils.setField(expectedJob, "id", 1L);

        given(connectionRepository.findByMemberId(1L)).willReturn(Optional.of(connection));
        given(syncJobRepository.existsBySubmissionId(100L)).willReturn(false);
        given(
            syncJobRepository
                .insertPendingJobIfAbsent(eq(10L), eq(100L), eq(200L), eq("두 수의 합"), eq(1), any(LocalDateTime.class))
        ).willReturn(1);
        given(syncJobRepository.findBySubmissionId(100L)).willReturn(Optional.of(expectedJob));

        Optional<GithubSyncJob> result = syncJobService.registerSyncJob(1L, 100L, 200L, "두 수의 합", 1);

        assertThat(result).isPresent();
        GithubSyncJob job = result.get();
        assertThat(job.getId()).isEqualTo(1L);
        assertThat(job.getStatus()).isEqualTo(GithubSyncJobStatus.PENDING);
        assertThat(job.getSubmissionId()).isEqualTo(100L);
        assertThat(job.getProblemId()).isEqualTo(200L);
        assertThat(job.getProblemTitle()).isEqualTo("두 수의 합");
        assertThat(job.getProblemDifficulty()).isEqualTo(1);
        verify(syncJobRepository)
            .insertPendingJobIfAbsent(eq(10L), eq(100L), eq(200L), eq("두 수의 합"), eq(1), any(LocalDateTime.class));
    }

    @Test
    @DisplayName("연동 해제 시 대기 상태의 작업들을 CANCELED 상태로 전이한다")
    void cancelPendingJobsSuccess() {
        GithubConnection connection = createConnection(true);
        GithubSyncJob job1 = GithubSyncJob.createPending(connection, 101L, 201L, "문제 1", 1);
        GithubSyncJob job2 = GithubSyncJob.createPending(connection, 102L, 202L, "문제 2", 2);

        given(syncJobRepository.findByConnectionIdAndStatusIn(any(), any())).willReturn(List.of(job1, job2));

        int canceledCount = syncJobService.cancelPendingJobs(10L, "연동 해제");

        assertThat(canceledCount).isEqualTo(2);
        assertThat(job1.getStatus()).isEqualTo(GithubSyncJobStatus.CANCELED);
        assertThat(job2.getStatus()).isEqualTo(GithubSyncJobStatus.CANCELED);
    }
}
