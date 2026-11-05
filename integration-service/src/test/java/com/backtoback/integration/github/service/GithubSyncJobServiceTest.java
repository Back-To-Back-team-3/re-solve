package com.backtoback.integration.github.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import com.backtoback.common.api.PageResponse;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.integration.github.client.JudgeServiceClient;
import com.backtoback.integration.github.client.dto.AcceptedSubmissionCodeResponse;
import com.backtoback.integration.github.domain.GithubConnection;
import com.backtoback.integration.github.domain.GithubDisconnectedReason;
import com.backtoback.integration.github.domain.GithubSyncJob;
import com.backtoback.integration.github.domain.GithubSyncJobStatus;
import com.backtoback.integration.github.dto.response.GithubManualSyncResponse;
import com.backtoback.integration.github.dto.response.GithubSyncJobItemResponse;
import com.backtoback.integration.github.repository.GithubConnectionRepository;
import com.backtoback.integration.github.repository.GithubSyncJobRepository;
import com.backtoback.integration.global.error.IntegrationErrorCode;
import com.backtoback.integration.global.error.IntegrationException;

@ExtendWith(MockitoExtension.class)
class GithubSyncJobServiceTest {

    @Mock
    private GithubConnectionRepository connectionRepository;

    @Mock
    private GithubSyncJobRepository syncJobRepository;

    @Mock
    private JudgeServiceClient judgeServiceClient;

    private Clock fixedClock;
    private GithubSyncJobService syncJobService;

    @BeforeEach
    void setUp() {
        fixedClock = Clock.fixed(Instant.parse("2026-10-29T10:00:00Z"), ZoneId.of("Asia/Seoul"));
        syncJobService
            = new GithubSyncJobService(connectionRepository, syncJobRepository, judgeServiceClient, fixedClock);
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

    @Test
    @DisplayName("8.1.6 연동 이력이 없는 회원이 목록 조회 시 빈 페이지를 반환한다")
    void getSyncJobsWhenNotConnectedReturnsEmptyPage() {
        given(connectionRepository.findByMemberId(1L)).willReturn(Optional.empty());

        PageResponse<GithubSyncJobItemResponse> result = syncJobService.getSyncJobs(1L, null, 0, 20, "createdAt,desc");

        assertThat(result.content()).isEmpty();
        assertThat(result.totalElements()).isZero();
        assertThat(result.page()).isZero();
        assertThat(result.size()).isEqualTo(20);
    }

    @Test
    @DisplayName("8.1.6 상태 필터와 정렬 조건으로 동기화 작업 목록을 조회한다")
    void getSyncJobsWithStatusFilterSuccess() {
        GithubConnection connection = createConnection(true);
        GithubSyncJob job = GithubSyncJob.createPending(connection, 91023L, 301L, "두 수의 합", 1);
        job.markFailed("Rate limit exceeded");
        ReflectionTestUtils.setField(job, "id", 77L);
        ReflectionTestUtils.setField(job, "retryCount", 5);
        ReflectionTestUtils.setField(job, "createdAt", LocalDateTime.of(2026, 10, 29, 19, 0));
        ReflectionTestUtils.setField(job, "updatedAt", LocalDateTime.of(2026, 10, 29, 19, 5));

        given(connectionRepository.findByMemberId(1L)).willReturn(Optional.of(connection));
        given(
            syncJobRepository.findByConnectionIdAndStatus(eq(10L), eq(GithubSyncJobStatus.FAILED), any(Pageable.class))
        ).willReturn(new PageImpl<>(List.of(job)));

        PageResponse<GithubSyncJobItemResponse> result
            = syncJobService.getSyncJobs(1L, "FAILED", 0, 20, "createdAt,desc");

        assertThat(result.content()).hasSize(1);
        GithubSyncJobItemResponse item = result.content().get(0);
        assertThat(item.syncJobId()).isEqualTo("77");
        assertThat(item.submissionId()).isEqualTo("91023");
        assertThat(item.problemId()).isEqualTo("301");
        assertThat(item.status()).isEqualTo("FAILED");
        assertThat(item.retryCount()).isEqualTo(5);
        assertThat(item.errorMessage()).isEqualTo("Rate limit exceeded");
        assertThat(item.createdAt()).isNotNull();
    }

    @Test
    @DisplayName("8.1.6 허용되지 않은 정렬 필드나 잘못된 상태 필터는 COMMON_INVALID_REQUEST 예외가 발생한다")
    void getSyncJobsInvalidParametersThrowsException() {
        assertThatThrownBy(() -> syncJobService.getSyncJobs(1L, null, -1, 20, "createdAt,desc"))
            .isInstanceOfSatisfying(
                IntegrationException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_INVALID_REQUEST)
            );

        assertThatThrownBy(() -> syncJobService.getSyncJobs(1L, null, 0, 101, "createdAt,desc"))
            .isInstanceOfSatisfying(
                IntegrationException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_INVALID_REQUEST)
            );

        assertThatThrownBy(() -> syncJobService.getSyncJobs(1L, null, 0, 20, "updatedAt,desc"))
            .isInstanceOfSatisfying(
                IntegrationException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_INVALID_REQUEST)
            );

        assertThatThrownBy(() -> syncJobService.getSyncJobs(1L, "INVALID_STATUS", 0, 20, "createdAt,desc"))
            .isInstanceOfSatisfying(
                IntegrationException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_INVALID_REQUEST)
            );
    }

    @Test
    @DisplayName("8.1.7 수동 동기화 요청 시 미연동 상태이면 GITHUB_NOT_CONNECTED(404) 예외가 발생한다")
    void requestManualSyncWhenNotConnected() {
        given(connectionRepository.findByMemberId(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> syncJobService.requestManualSync(1L, "91023"))
            .isInstanceOfSatisfying(
                IntegrationException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(IntegrationErrorCode.GITHUB_NOT_CONNECTED)
            );
    }

    @Test
    @DisplayName("8.1.7 이미 대기 또는 실행 중인 작업이 있으면 상태 변경 없이 기존 작업을 멱등하게 반환한다")
    void requestManualSyncIdempotentWhenPendingOrRunning() {
        GithubConnection connection = createConnection(true);
        GithubSyncJob existingJob = GithubSyncJob.createPending(connection, 91023L, 301L, "두 수의 합", 1);
        existingJob.markRunning();
        ReflectionTestUtils.setField(existingJob, "id", 77L);

        given(connectionRepository.findByMemberId(1L)).willReturn(Optional.of(connection));
        given(syncJobRepository.findBySubmissionIdWithConnection(91023L)).willReturn(Optional.of(existingJob));

        GithubManualSyncResponse response = syncJobService.requestManualSync(1L, "91023");

        assertThat(response.syncJobId()).isEqualTo("77");
        assertThat(response.submissionId()).isEqualTo("91023");
        assertThat(response.status()).isEqualTo("RUNNING");
        verify(judgeServiceClient, never()).verifyAndGetAcceptedCodeForManualSync(any(), any());
    }

    @Test
    @DisplayName("8.1.7 FAILED 상태의 작업을 수동 동기화 요청하면 retryCount=0 및 PENDING으로 초기화하여 반환한다")
    void requestManualSyncResetsFailedJobToPending() {
        GithubConnection connection = createConnection(true);
        GithubSyncJob failedJob = GithubSyncJob.createPending(connection, 91023L, 301L, "두 수의 합", 1);
        failedJob.markRetryWaiting(LocalDateTime.now().plusMinutes(1), "Temp error");
        failedJob.markFailed("Rate limit exceeded");
        ReflectionTestUtils.setField(failedJob, "id", 77L);

        given(connectionRepository.findByMemberId(1L)).willReturn(Optional.of(connection));
        given(syncJobRepository.findBySubmissionIdWithConnection(91023L)).willReturn(Optional.of(failedJob));
        given(judgeServiceClient.verifyAndGetAcceptedCodeForManualSync(91023L, 1L))
            .willReturn(
                new AcceptedSubmissionCodeResponse("91023", "301", "java", "class Solution {}", 1, OffsetDateTime.now())
            );

        GithubManualSyncResponse response = syncJobService.requestManualSync(1L, "91023");

        assertThat(response.syncJobId()).isEqualTo("77");
        assertThat(response.status()).isEqualTo("PENDING");
        assertThat(failedJob.getRetryCount()).isZero();
        assertThat(failedJob.getErrorMessage()).isNull();
    }

    @Test
    @DisplayName("8.1.7 이미 SUCCEEDED 상태이거나 타 회원의 제출이면 GITHUB_SYNC_NOT_ALLOWED(409) 예외가 발생한다")
    void requestManualSyncRejectsSucceededOrOtherMemberJob() {
        GithubConnection connection = createConnection(true);
        GithubSyncJob succeededJob = GithubSyncJob.createPending(connection, 91023L, 301L, "두 수의 합", 1);
        succeededJob.markSucceeded("sha123");

        given(connectionRepository.findByMemberId(1L)).willReturn(Optional.of(connection));
        given(syncJobRepository.findBySubmissionIdWithConnection(91023L)).willReturn(Optional.of(succeededJob));

        assertThatThrownBy(() -> syncJobService.requestManualSync(1L, "91023"))
            .isInstanceOfSatisfying(
                IntegrationException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(IntegrationErrorCode.GITHUB_SYNC_NOT_ALLOWED)
            );
    }

    @Test
    @DisplayName("8.1.7 기존 작업이 없는 제출(연동 전 AC 제출)은 judge 검증 후 신규 PENDING 작업을 등록한다")
    void requestManualSyncCreatesNewPendingJobWhenAbsent() {
        GithubConnection connection = createConnection(true);
        GithubSyncJob createdJob = GithubSyncJob.createPending(connection, 91023L, 301L, "problem-301", 1);
        ReflectionTestUtils.setField(createdJob, "id", 88L);

        given(connectionRepository.findByMemberId(1L)).willReturn(Optional.of(connection));
        given(syncJobRepository.findBySubmissionIdWithConnection(91023L))
            .willReturn(Optional.empty())
            .willReturn(Optional.of(createdJob));
        given(judgeServiceClient.verifyAndGetAcceptedCodeForManualSync(91023L, 1L))
            .willReturn(
                new AcceptedSubmissionCodeResponse("91023", "301", "java", "class Solution {}", 1, OffsetDateTime.now())
            );

        GithubManualSyncResponse response = syncJobService.requestManualSync(1L, "91023");

        assertThat(response.syncJobId()).isEqualTo("88");
        assertThat(response.submissionId()).isEqualTo("91023");
        assertThat(response.status()).isEqualTo("PENDING");
        verify(
            syncJobRepository
        ).insertPendingJobIfAbsent(eq(10L), eq(91023L), eq(301L), eq("problem-301"), eq(1), any(LocalDateTime.class));
    }
}
