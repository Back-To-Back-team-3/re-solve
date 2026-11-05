package com.backtoback.integration.github.domain;

import java.time.LocalDateTime;

import com.backtoback.common.jpa.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * GitHub 자동 커밋 작업 엔티티 (erd.md > 4.8.2, scheduler.md > 8.1).
 * 맞힌 문제(AC) 제출 건에 대한 GitHub 커밋 진행 상태와 재시도 이력을 관리한다.
 */
@Entity
@Table(name = "github_sync_jobs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GithubSyncJob extends BaseTimeEntity {

    private static final int MAX_ERROR_MESSAGE_LENGTH = 500;
    private static final int MAX_PROBLEM_TITLE_LENGTH = 200;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(
        name = "connection_id",
        nullable = false
    )
    private GithubConnection connection;

    @Column(
        name = "submission_id",
        nullable = false,
        unique = true
    )
    private Long submissionId;

    @Column(
        name = "problem_id",
        nullable = false
    )
    private Long problemId;

    @Column(
        name = "problem_title",
        nullable = false,
        length = MAX_PROBLEM_TITLE_LENGTH
    )
    private String problemTitle;

    @Column(
        name = "problem_difficulty",
        nullable = false
    )
    private int problemDifficulty;

    @Column(
        name = "commit_sha",
        length = 64
    )
    private String commitSha;

    @Enumerated(EnumType.STRING)
    @Column(
        name = "status",
        nullable = false,
        length = 30
    )
    private GithubSyncJobStatus status;

    @Column(
        name = "retry_count",
        nullable = false
    )
    private int retryCount;

    @Column(name = "next_retry_at")
    private LocalDateTime nextRetryAt;

    @Column(
        name = "error_message",
        length = MAX_ERROR_MESSAGE_LENGTH
    )
    private String errorMessage;

    private GithubSyncJob(
        GithubConnection connection,
        Long submissionId,
        Long problemId,
        String problemTitle,
        int problemDifficulty
    ) {
        if (connection == null) {
            throw new IllegalArgumentException("connection은 필수입니다.");
        }
        if (submissionId == null) {
            throw new IllegalArgumentException("submissionId는 필수입니다.");
        }
        if (problemId == null) {
            throw new IllegalArgumentException("problemId는 필수입니다.");
        }
        if (problemTitle == null || problemTitle.isBlank()) {
            throw new IllegalArgumentException("problemTitle은 필수입니다.");
        }
        if (problemDifficulty < 1 || problemDifficulty > 3) {
            throw new IllegalArgumentException("problemDifficulty는 1~3이어야 합니다.");
        }

        this.connection = connection;
        this.submissionId = submissionId;
        this.problemId = problemId;
        this.problemTitle = truncateByCodePoints(problemTitle, MAX_PROBLEM_TITLE_LENGTH);
        this.problemDifficulty = problemDifficulty;
        this.status = GithubSyncJobStatus.PENDING;
        this.retryCount = 0;
        this.nextRetryAt = null;
        this.errorMessage = null;
    }

    private static String truncateByCodePoints(String str, int maxCodePoints) {
        if (str == null) {
            return null;
        }
        int codePointCount = str.codePointCount(0, str.length());
        if (codePointCount <= maxCodePoints) {
            return str;
        }
        int endOffset = str.offsetByCodePoints(0, maxCodePoints);
        return str.substring(0, endOffset);
    }

    /**
     * 대기 상태의 신규 GitHub 자동 커밋 작업을 생성한다.
     *
     * @param connection 연동 정보
     * @param submissionId 제출 ID
     * @param problemId 문제 ID
     * @param problemTitle 문제명 (커밋 경로·알림용 이벤트 스냅샷)
     * @param problemDifficulty 문제 난이도 (1~3, 이벤트 스냅샷)
     * @return 생성된 작업 엔티티
     */
    public static GithubSyncJob createPending(
        GithubConnection connection,
        Long submissionId,
        Long problemId,
        String problemTitle,
        int problemDifficulty
    ) {
        return new GithubSyncJob(connection, submissionId, problemId, problemTitle, problemDifficulty);
    }

    /**
     * 작업을 실행 중 상태로 전이한다.
     */
    public void markRunning() {
        this.status = GithubSyncJobStatus.RUNNING;
        this.nextRetryAt = null;
    }

    /**
     * 작업을 성공 상태로 전이하고 커밋 SHA를 기록한다.
     *
     * @param commitSha 생성된 GitHub 커밋 SHA
     */
    public void markSucceeded(String commitSha) {
        this.status = GithubSyncJobStatus.SUCCEEDED;
        this.commitSha = commitSha;
        this.nextRetryAt = null;
        this.errorMessage = null;
    }

    /**
     * GitHub Rate Limit 초과 시 작업을 RATE_LIMITED 상태로 전이한다.
     *
     * @param resetAt Rate Limit 해제 시각
     */
    public void markRateLimited(LocalDateTime resetAt) {
        this.status = GithubSyncJobStatus.RATE_LIMITED;
        this.nextRetryAt = resetAt;
        this.errorMessage = "GitHub API Rate Limit exceeded";
    }

    /**
     * 일시적 오류 발생 시 작업을 재시도 대기 상태로 전이한다.
     *
     * @param nextRetryAt 다음 재시도 시각
     * @param errorMessage 오류 메시지
     */
    public void markRetryWaiting(LocalDateTime nextRetryAt, String errorMessage) {
        this.status = GithubSyncJobStatus.RETRY_WAITING;
        this.retryCount++;
        this.nextRetryAt = nextRetryAt;
        this.errorMessage = truncateErrorMessage(errorMessage);
    }

    /**
     * 재시도 한도 초과 또는 복구 불가능한 오류 발생 시 작업을 실패 상태로 전이한다.
     *
     * @param errorMessage 실패 사유
     */
    public void markFailed(String errorMessage) {
        this.status = GithubSyncJobStatus.FAILED;
        this.nextRetryAt = null;
        this.errorMessage = truncateErrorMessage(errorMessage);
    }

    /**
     * 연동 해제 등에 의해 대기 중인 작업을 취소한다.
     *
     * @param reason 취소 사유
     */
    public void cancel(String reason) {
        this.status = GithubSyncJobStatus.CANCELED;
        this.nextRetryAt = null;
        this.errorMessage = truncateErrorMessage(reason);
    }

    /**
     * 작업이 현재 대기 중이거나 실행 중인지 확인한다 (api-spec.md > 8.1.7 멱등 반환 조건).
     *
     * @return PENDING, RUNNING, RETRY_WAITING, RATE_LIMITED 중 하나이면 true
     */
    public boolean isInProgressOrPending() {
        return this.status == GithubSyncJobStatus.PENDING || this.status == GithubSyncJobStatus.RUNNING
            || this.status == GithubSyncJobStatus.RETRY_WAITING || this.status == GithubSyncJobStatus.RATE_LIMITED;
    }

    /**
     * 수동 동기화 요청으로 재시도(PENDING 초기화)가 가능한 상태인지 확인한다 (api-spec.md > 8.1.7).
     *
     * @return FAILED 또는 CANCELED 상태이면 true
     */
    public boolean isRetryableByManualRequest() {
        return this.status == GithubSyncJobStatus.FAILED || this.status == GithubSyncJobStatus.CANCELED;
    }

    /**
     * 수동 동기화 요청에 의해 실패(FAILED) 또는 취소(CANCELED)된 작업을 대기(PENDING) 상태로 초기화한다 (api-spec.md > 8.1.7).
     *
     * @param activeConnection 현재 활성화된 연동 정보
     */
    public void resetForManualRetry(GithubConnection activeConnection) {
        if (activeConnection == null) {
            throw new IllegalArgumentException("activeConnection은 필수입니다.");
        }
        if (!isRetryableByManualRequest()) {
            throw new IllegalStateException("FAILED 또는 CANCELED 상태의 작업만 수동 재시도할 수 있습니다: status=" + this.status);
        }
        this.connection = activeConnection;
        this.status = GithubSyncJobStatus.PENDING;
        this.retryCount = 0;
        this.nextRetryAt = null;
        this.errorMessage = null;
    }

    private static String truncateErrorMessage(String message) {
        if (message == null) {
            return null;
        }
        return message.length() > MAX_ERROR_MESSAGE_LENGTH ? message.substring(0, MAX_ERROR_MESSAGE_LENGTH) : message;
    }
}
