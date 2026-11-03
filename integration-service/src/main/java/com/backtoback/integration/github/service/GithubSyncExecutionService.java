package com.backtoback.integration.github.service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.common.time.ClockConfiguration;
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
import com.backtoback.integration.github.domain.GithubSyncJobStatus;
import com.backtoback.integration.github.event.dto.GithubSyncFailedEvent;
import com.backtoback.integration.github.repository.GithubConnectionRepository;
import com.backtoback.integration.github.repository.GithubSyncJobRepository;
import com.backtoback.integration.infra.event.domain.OutboxEvent;
import com.backtoback.integration.infra.event.repository.OutboxEventRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.ObjectMapper;

/**
 * 단일 GitHub 자동 커밋 작업(Sync Job)을 실행하고 상태 전이 및 Outbox 이벤트를 발행하는 서비스
 * (scheduler.md > 8.1, event-contract.md > 4.5 I1).
 * 외부 HTTP 호출 중 DB 커넥션 점유를 방지하기 위해 트랜잭션을 분리하고,
 * 결과 반영 시 TransactionTemplate과 조건부 UPDATE(WHERE id = :id AND status = 'RUNNING')를 사용한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubSyncExecutionService {

    public static final int MAX_RETRY_COUNT = 5;

    private final GithubSyncJobRepository syncJobRepository;
    private final GithubConnectionRepository connectionRepository;
    private final GithubAppClient githubAppClient;
    private final JudgeServiceClient judgeServiceClient;
    private final GithubCommitPathResolver pathResolver;
    private final GithubSyncJobService syncJobService;
    private final OutboxEventRepository outboxEventRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactionTemplate;

    /**
     * 선점된 단일 Sync Job을 실행한다.
     * 외부 API 호출은 트랜잭션 밖에서 수행되며, 결과 반영은 TransactionTemplate을 통해
     * 조건부 UPDATE(WHERE id = :id AND status = 'RUNNING')로 격리된다.
     *
     * @param jobId 실행 대상 작업 ID
     */
    public void executeSyncJob(Long jobId) {
        // 1. Fetch Join으로 연동 정보를 함께 로드하여 LAZY 초기화 예외 방지
        Optional<GithubSyncJob> jobOpt = syncJobRepository.findByIdWithConnection(jobId);
        if (jobOpt.isEmpty()) {
            log.warn("실행 대상 Sync Job을 찾을 수 없습니다: jobId={}", jobId);
            return;
        }

        GithubSyncJob job = jobOpt.get();
        if (job.getStatus() != GithubSyncJobStatus.RUNNING) {
            log.info("RUNNING 상태가 아니므로 실행을 건너뜁니다: jobId={}, status={}", jobId, job.getStatus());
            return;
        }

        GithubConnection connection = job.getConnection();
        if (connection == null || connection.getStatus() != GithubConnectionStatus.CONNECTED
            || connection.getInstallationId() == null) {
            log.info("연동이 해제되었거나 설치 정보가 없어 작업을 취소합니다: jobId={}", jobId);
            cancelJobIfRunning(jobId, "GitHub 연동 해제 상태");
            return;
        }

        // 트랜잭션 분리 환경에서 안전하게 사용할 작업 및 연동 정보 스냅샷
        Long connectionId = connection.getId();
        Long memberId = connection.getMemberId();
        Long installationId = connection.getInstallationId();
        String repoOwner = connection.getRepositoryOwner();
        String repoName = connection.getRepositoryName();
        String commitPathRule = connection.getCommitPathRule();
        Long submissionId = job.getSubmissionId();
        int difficulty = job.getProblemDifficulty();
        Long problemId = job.getProblemId();
        String problemTitle = job.getProblemTitle();
        int currentRetryCount = job.getRetryCount();

        String commitPath = null;
        try {
            // 1. GitHub App 설치 Access Token 발급 (1시간 유효)
            GithubInstallationTokenResponse tokenResponse
                = githubAppClient.createInstallationAccessToken(installationId);
            String installationToken = tokenResponse.token();

            // 2. judge-service 정답 코드 조회
            AcceptedSubmissionCodeResponse codeResponse = judgeServiceClient.getAcceptedCode(submissionId, memberId);

            // 3. 커밋 경로 및 커밋 메시지 생성
            commitPath
                = pathResolver
                    .resolvePath(commitPathRule, difficulty, problemId, problemTitle, codeResponse.language());
            String commitMessage
                = String.format("Solve: [%d] %s (%s)", difficulty, problemTitle, codeResponse.language());

            // 4. 기존 파일 SHA 조회 (덮어쓰기 지원)
            Optional<String> existingSha
                = githubAppClient.findContentSha(installationToken, repoOwner, repoName, commitPath);

            // 5. GitHub Contents API PUT 커밋 실행
            GithubCommitResultResponse commitResult
                = githubAppClient
                    .putContent(
                        installationToken,
                        repoOwner,
                        repoName,
                        commitPath,
                        commitMessage,
                        codeResponse.sourceCode(),
                        existingSha.orElse(null)
                    );

            // 6. 결과 반영 (조건부 UPDATE)
            boolean updated = recordSuccess(jobId, commitResult.commitSha());
            if (updated) {
                log
                    .info(
                        "GitHub 자동 커밋 성공: jobId={}, commitSha={}, path={}",
                        jobId,
                        commitResult.commitSha(),
                        commitPath
                    );
            } else {
                log.warn("이미 상태가 전이되어 커밋 성공 반영을 건너뜁니다: jobId={}", jobId);
            }

        } catch (GithubApiException exception) {
            handleGithubApiException(
                jobId,
                memberId,
                connectionId,
                submissionId,
                problemId,
                problemTitle,
                currentRetryCount,
                exception,
                commitPath
            );
        } catch (BusinessException exception) {
            log.warn("내부 서비스 비즈니스 오류 발생: jobId={}, error={}", jobId, exception.getMessage());
            handleRetryOrFailure(
                jobId,
                memberId,
                submissionId,
                problemId,
                problemTitle,
                currentRetryCount,
                "내부 의존성 오류: " + exception.getMessage(),
                "CODE_UNAVAILABLE"
            );
        } catch (Exception exception) {
            log.error("Sync Job 실행 중 예상치 못한 오류 발생: jobId={}, error={}", jobId, exception.getMessage(), exception);
            handleRetryOrFailure(
                jobId,
                memberId,
                submissionId,
                problemId,
                problemTitle,
                currentRetryCount,
                "실행 오류: " + exception.getMessage(),
                "GITHUB_5XX"
            );
        }
    }

    private void handleGithubApiException(
        Long jobId,
        Long memberId,
        Long connectionId,
        Long submissionId,
        Long problemId,
        String problemTitle,
        int currentRetryCount,
        GithubApiException exception,
        String commitPath
    ) {
        if (exception.isUnauthorized()) {
            // 401: 권한 만료 -> 연동 DISCONNECTED 전이, 대기 작업 일괄 CANCELED, GitHubSyncFailed(AUTH_EXPIRED)
            log.warn("GitHub 인증 권한 만료 (401): jobId={}, memberId={}", jobId, memberId);
            recordAuthExpired(
                jobId,
                memberId,
                connectionId,
                submissionId,
                problemId,
                problemTitle,
                "GitHub 인증 권한 만료 (401)"
            );
        } else if (exception.isRateLimited()) {
            // 403 / 429: Rate Limit 초과
            LocalDateTime resetAt = resolveRateLimitResetAt(exception.getRateLimitResetAt());
            log.warn("GitHub API Rate Limit 초과: jobId={}, resetAt={}", jobId, resetAt);
            recordRateLimited(jobId, resetAt, "Rate limit exceeded (" + exception.getStatusCode() + ")");
        } else if (exception.isNotFound()) {
            // 404: 저장소를 찾을 수 없음
            log.warn("GitHub 저장소 또는 경로를 찾을 수 없음: jobId={}, path={}", jobId, commitPath);
            handleRetryOrFailure(
                jobId,
                memberId,
                submissionId,
                problemId,
                problemTitle,
                currentRetryCount,
                "저장소 또는 경로를 찾을 수 없음 (404)",
                "REPOSITORY_NOT_FOUND"
            );
        } else {
            // 409 sha 충돌 또는 5xx
            log.warn("GitHub API 응답 오류: jobId={}, status={}", jobId, exception.getStatusCode());
            handleRetryOrFailure(
                jobId,
                memberId,
                submissionId,
                problemId,
                problemTitle,
                currentRetryCount,
                "GitHub API 오류: " + exception.getMessage(),
                "GITHUB_5XX"
            );
        }
    }

    private LocalDateTime resolveRateLimitResetAt(Instant resetInstant) {
        if (resetInstant != null) {
            return LocalDateTime.ofInstant(resetInstant, ClockConfiguration.SERVICE_ZONE);
        }
        return LocalDateTime.now(clock).plusMinutes(1);
    }

    /**
     * 성공 상태(SUCCEEDED)로 조건부 UPDATE한다.
     */
    public boolean recordSuccess(Long jobId, String commitSha) {
        return Boolean.TRUE.equals(transactionTemplate.execute(status -> {
            LocalDateTime now = LocalDateTime.now(clock);
            int affected = syncJobRepository.updateStatusToSucceeded(jobId, commitSha, now);
            return affected == 1;
        }));
    }

    /**
     * Rate Limit 상태로 조건부 UPDATE한다.
     */
    public void recordRateLimited(Long jobId, LocalDateTime resetAt, String errorMessage) {
        transactionTemplate.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now(clock);
            int affected = syncJobRepository.updateStatusToRateLimited(jobId, resetAt, errorMessage, now);
            if (affected != 1) {
                log.info("작업 상태가 RUNNING이 아니므로 Rate Limit 전이를 생략합니다: jobId={}", jobId);
            }
        });
    }

    /**
     * 일시 오류에 대해 지수 백오프로 재시도 대기하거나, 5회 초과 시 최종 실패(FAILED) 처리하고 Outbox 이벤트를 발행한다.
     */
    public void handleRetryOrFailure(
        Long jobId,
        Long memberId,
        Long submissionId,
        Long problemId,
        String problemTitle,
        int currentRetryCount,
        String errorMessage,
        String errorCode
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now(clock);
            if (currentRetryCount >= MAX_RETRY_COUNT) {
                int affected = syncJobRepository.updateStatusToFailed(jobId, errorMessage, now);
                if (affected == 1) {
                    publishSyncFailedOutboxEvent(
                        jobId,
                        memberId,
                        submissionId,
                        problemId,
                        problemTitle,
                        errorCode,
                        now
                    );
                    log.warn("Sync Job 최대 재시도 횟수 초과로 최종 실패: jobId={}, retries={}", jobId, currentRetryCount);
                }
            } else {
                Duration backoff = calculateBackoffDuration(currentRetryCount);
                LocalDateTime nextRetryAt = now.plus(backoff);
                int affected = syncJobRepository.updateStatusToRetryWaiting(jobId, nextRetryAt, errorMessage, now);
                if (affected == 1) {
                    log
                        .info(
                            "Sync Job 재시도 대기 전이: jobId={}, nextRetryAt={}, retryCount={}",
                            jobId,
                            nextRetryAt,
                            currentRetryCount + 1
                        );
                }
            }
        });
    }

    /**
     * 401 권한 만료 시 연동을 DISCONNECTED로 전이하고, 작업을 FAILED로 종료하며, Outbox 이벤트를 발행한다.
     */
    public void recordAuthExpired(
        Long jobId,
        Long memberId,
        Long connectionId,
        Long submissionId,
        Long problemId,
        String problemTitle,
        String errorMessage
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            Optional<GithubConnection> connOpt = connectionRepository.findById(connectionId);
            connOpt.ifPresent(conn -> {
                if (conn.getStatus() == GithubConnectionStatus.CONNECTED) {
                    conn.disconnect(GithubDisconnectedReason.AUTH_EXPIRED);
                }
            });

            syncJobService.cancelPendingJobs(connectionId, "GitHub 인증 권한 만료");

            LocalDateTime now = LocalDateTime.now(clock);
            int affected = syncJobRepository.updateStatusToFailed(jobId, errorMessage, now);
            if (affected == 1) {
                publishSyncFailedOutboxEvent(
                    jobId,
                    memberId,
                    submissionId,
                    problemId,
                    problemTitle,
                    "AUTH_EXPIRED",
                    now
                );
            }
        });
    }

    /**
     * 작업을 조건부로 취소(CANCELED) 상태로 반영한다.
     */
    public void cancelJobIfRunning(Long jobId, String reason) {
        transactionTemplate.executeWithoutResult(status -> {
            LocalDateTime now = LocalDateTime.now(clock);
            syncJobRepository.updateStatusToCanceled(jobId, reason, now);
        });
    }

    /**
     * 재시도 횟수에 따른 지수 백오프 시간을 반환한다 (1분 → 5분 → 30분).
     */
    public static Duration calculateBackoffDuration(int currentRetryCount) {
        if (currentRetryCount <= 0) {
            return Duration.ofMinutes(1);
        } else if (currentRetryCount == 1) {
            return Duration.ofMinutes(5);
        } else {
            return Duration.ofMinutes(30);
        }
    }

    private void publishSyncFailedOutboxEvent(
        Long jobId,
        Long memberId,
        Long submissionId,
        Long problemId,
        String problemTitle,
        String errorCode,
        LocalDateTime now
    ) {
        try {
            GithubSyncFailedEvent payload
                = GithubSyncFailedEvent.create(jobId, memberId, submissionId, problemId, problemTitle, errorCode, now);

            String correlationId
                = Optional
                    .ofNullable(MDC.get("correlationId"))
                    .orElseGet(() -> "c-" + UUID.randomUUID().toString().substring(0, 8));

            OutboxEvent outboxEvent
                = OutboxEvent
                    .builder()
                    .eventId(UUID.randomUUID().toString())
                    .eventType("GitHubSyncFailed")
                    .schemaVersion("v1")
                    .aggregateType("syncJob")
                    .aggregateId(String.valueOf(jobId))
                    .correlationId(correlationId)
                    .payload(objectMapper.writeValueAsString(payload))
                    .occurredAt(now)
                    .build();

            outboxEventRepository.save(outboxEvent);
            log.info("GitHubSyncFailed Outbox 이벤트 저장 완료: syncJobId={}, errorCode={}", jobId, errorCode);
        } catch (Exception e) {
            log.error("GitHubSyncFailed Outbox 이벤트 저장 실패: syncJobId={}", jobId, e);
        }
    }
}
