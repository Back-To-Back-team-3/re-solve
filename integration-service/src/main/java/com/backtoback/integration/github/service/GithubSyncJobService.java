package com.backtoback.integration.github.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.api.PageResponse;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.exception.ErrorCode;
import com.backtoback.integration.github.client.JudgeServiceClient;
import com.backtoback.integration.github.client.dto.AcceptedSubmissionCodeResponse;
import com.backtoback.integration.github.domain.GithubConnection;
import com.backtoback.integration.github.domain.GithubConnectionStatus;
import com.backtoback.integration.github.domain.GithubSyncJob;
import com.backtoback.integration.github.domain.GithubSyncJobStatus;
import com.backtoback.integration.github.dto.response.GithubManualSyncResponse;
import com.backtoback.integration.github.dto.response.GithubSyncJobItemResponse;
import com.backtoback.integration.github.repository.GithubConnectionRepository;
import com.backtoback.integration.github.repository.GithubSyncJobRepository;
import com.backtoback.integration.global.error.IntegrationErrorCode;
import com.backtoback.integration.global.error.IntegrationException;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * GitHub 자동 커밋 작업 등록 및 상태 관리 서비스 (erd.md > 4.8.2, policy-and-state.md > 9.3, api-spec.md > 8.1.6, 8.1.7).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GithubSyncJobService {

    private static final List<GithubSyncJobStatus> CANCELABLE_STATUSES
        = List.of(GithubSyncJobStatus.PENDING, GithubSyncJobStatus.RETRY_WAITING, GithubSyncJobStatus.RATE_LIMITED);
    private static final int MAX_PAGE_SIZE = 100;
    private static final String ALLOWED_SORT_FIELD = "createdAt";
    private static final int DEFAULT_FALLBACK_DIFFICULTY = 1;

    private final GithubConnectionRepository connectionRepository;
    private final GithubSyncJobRepository syncJobRepository;
    private final JudgeServiceClient judgeServiceClient;
    private final Clock clock;

    /**
     * AC 판정된 제출 건에 대해 회원의 연동 상태 및 자동 커밋 설정을 확인한 후 신규 Sync Job을 등록한다 (policy-and-state.md > 9.3).
     *
     * @param memberId 회원 ID
     * @param submissionId 제출 ID
     * @param problemId 문제 ID
     * @param problemTitle 문제명
     * @param problemDifficulty 문제 난이도 (1~3)
     * @return 생성된 작업 Optional (연동 미완료, 비활성화 또는 중복 시 empty)
     */
    @Transactional
    public Optional<GithubSyncJob> registerSyncJob(
        Long memberId,
        Long submissionId,
        Long problemId,
        String problemTitle,
        int problemDifficulty
    ) {
        Optional<GithubConnection> connectionOpt = connectionRepository.findByMemberId(memberId);
        if (connectionOpt.isEmpty()) {
            log.debug("GitHub 연동 정보가 없어 Sync Job 생성을 생략합니다: memberId={}", memberId);
            return Optional.empty();
        }

        GithubConnection connection = connectionOpt.get();
        if (connection.getStatus() != GithubConnectionStatus.CONNECTED) {
            log.debug("GitHub 연동 상태가 CONNECTED가 아니므로 생략합니다: memberId={}, status={}", memberId, connection.getStatus());
            return Optional.empty();
        }

        if (!connection.isAutoCommitEnabled()) {
            log.debug("GitHub 자동 커밋 설정이 비활성화되어 생략합니다: memberId={}", memberId);
            return Optional.empty();
        }

        if (syncJobRepository.existsBySubmissionId(submissionId)) {
            log.info("이미 등록된 제출 동기화 작업이 존재합니다: submissionId={}", submissionId);
            return Optional.empty();
        }

        // 코드 포인트 단위 자르기 및 유효성 검증을 거친 후 조건부 삽입
        GithubSyncJob pendingJob
            = GithubSyncJob.createPending(connection, submissionId, problemId, problemTitle, problemDifficulty);

        LocalDateTime now = LocalDateTime.now(clock);
        syncJobRepository
            .insertPendingJobIfAbsent(
                connection.getId(),
                submissionId,
                problemId,
                pendingJob.getProblemTitle(),
                problemDifficulty,
                now
            );

        Optional<GithubSyncJob> savedJob = syncJobRepository.findBySubmissionId(submissionId);
        savedJob
            .ifPresent(
                job -> log
                    .info(
                        "GitHub Sync Job 등록 확인: jobId={}, memberId={}, submissionId={}, problemId={}",
                        job.getId(),
                        memberId,
                        submissionId,
                        problemId
                    )
            );
        return savedJob;
    }

    /**
     * 연동 해제 시 대기 및 재시도 상태인 Sync Job을 일괄 취소(CANCELED) 상태로 전이한다 (api-spec.md > 8.1.5, policy-and-state.md > 9.3).
     *
     * @param connectionId 연동 ID
     * @param reason 취소 사유
     * @return 취소된 작업 건수
     */
    @Transactional
    public int cancelPendingJobs(Long connectionId, String reason) {
        List<GithubSyncJob> pendingJobs
            = syncJobRepository.findByConnectionIdAndStatusIn(connectionId, CANCELABLE_STATUSES);
        for (GithubSyncJob job : pendingJobs) {
            job.cancel(reason);
        }
        if (!pendingJobs.isEmpty()) {
            log
                .info(
                    "대기 중인 GitHub Sync Job 취소 처리 완료: connectionId={}, count={}, reason={}",
                    connectionId,
                    pendingJobs.size(),
                    reason
                );
        }
        return pendingJobs.size();
    }

    /**
     * 회원의 GitHub 동기화 작업 목록을 페이지네이션 조회한다 (api-spec.md > 8.1.6).
     * 연동 이력이 없는 회원은 빈 페이지(content: [])를 반환한다.
     *
     * @param memberId 회원 ID
     * @param statusParam 작업 상태 필터 (선택)
     * @param page 페이지 번호 (0부터 시작)
     * @param size 페이지 크기 (1~100)
     * @param sortParam 정렬 파라미터 (기본 createdAt,desc)
     * @return 동기화 작업 페이지 응답
     */
    @Transactional(readOnly = true)
    public PageResponse<GithubSyncJobItemResponse> getSyncJobs(
        Long memberId,
        String statusParam,
        int page,
        int size,
        String sortParam
    ) {
        if (page < 0 || size < 1 || size > MAX_PAGE_SIZE) {
            throw new IntegrationException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }

        Sort sort = parseAndValidateSort(sortParam);
        GithubSyncJobStatus statusFilter = parseStatusFilter(statusParam);

        Optional<GithubConnection> connectionOpt = connectionRepository.findByMemberId(memberId);
        if (connectionOpt.isEmpty()) {
            return new PageResponse<>(List.of(), page, size, 0L, 0, false);
        }

        Pageable pageable = PageRequest.of(page, size, sort);
        Long connectionId = connectionOpt.get().getId();
        Page<GithubSyncJob> jobPage
            = statusFilter == null ? syncJobRepository.findByConnectionId(connectionId, pageable)
                : syncJobRepository.findByConnectionIdAndStatus(connectionId, statusFilter, pageable);

        return PageResponse.from(jobPage.map(GithubSyncJobItemResponse::from));
    }

    /**
     * 누락되었거나 연동 전에 맞힌 제출, 혹은 실패/취소된 작업을 수동으로 동기화(커밋) 요청한다 (api-spec.md > 8.1.7).
     *
     * @param memberId 요청 회원 ID
     * @param submissionIdStr 제출 ID 문자열
     * @return 등록 또는 재시도 대기 전환된 작업 응답
     */
    @Transactional
    public GithubManualSyncResponse requestManualSync(Long memberId, String submissionIdStr) {
        Long submissionId = parsePositiveLong(submissionIdStr, CommonErrorCode.COMMON_INVALID_REQUEST);

        GithubConnection connection
            = connectionRepository
                .findByMemberId(memberId)
                .filter(conn -> conn.getStatus() == GithubConnectionStatus.CONNECTED)
                .orElseThrow(() -> new IntegrationException(IntegrationErrorCode.GITHUB_NOT_CONNECTED));

        Optional<GithubSyncJob> existingJobOpt = syncJobRepository.findBySubmissionIdWithConnection(submissionId);
        if (existingJobOpt.isPresent()) {
            GithubSyncJob existingJob = existingJobOpt.get();
            if (!existingJob.getConnection().getMemberId().equals(memberId)) {
                throw new IntegrationException(IntegrationErrorCode.GITHUB_SYNC_NOT_ALLOWED);
            }
            if (existingJob.isInProgressOrPending()) {
                return GithubManualSyncResponse.from(existingJob);
            }
            if (!existingJob.isRetryableByManualRequest()) {
                throw new IntegrationException(IntegrationErrorCode.GITHUB_SYNC_NOT_ALLOWED);
            }

            judgeServiceClient.verifyAndGetAcceptedCodeForManualSync(submissionId, memberId);
            existingJob.resetForManualRetry(connection);
            return GithubManualSyncResponse.from(existingJob);
        }

        AcceptedSubmissionCodeResponse acceptedCode
            = judgeServiceClient.verifyAndGetAcceptedCodeForManualSync(submissionId, memberId);
        Long problemId = parsePositiveLong(acceptedCode.problemId(), CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        String fallbackTitle = "problem-" + problemId;

        GithubSyncJob pendingJob
            = GithubSyncJob
                .createPending(connection, submissionId, problemId, fallbackTitle, DEFAULT_FALLBACK_DIFFICULTY);

        LocalDateTime now = LocalDateTime.now(clock);
        syncJobRepository
            .insertPendingJobIfAbsent(
                connection.getId(),
                submissionId,
                problemId,
                pendingJob.getProblemTitle(),
                DEFAULT_FALLBACK_DIFFICULTY,
                now
            );

        GithubSyncJob savedJob
            = syncJobRepository
                .findBySubmissionIdWithConnection(submissionId)
                .orElseThrow(() -> new IntegrationException(CommonErrorCode.COMMON_INTERNAL_SERVER_ERROR));
        if (!savedJob.getConnection().getMemberId().equals(memberId)) {
            throw new IntegrationException(IntegrationErrorCode.GITHUB_SYNC_NOT_ALLOWED);
        }

        return GithubManualSyncResponse.from(savedJob);
    }

    private Sort parseAndValidateSort(String sortParam) {
        if (sortParam == null || sortParam.isBlank()) {
            return Sort.by(Sort.Direction.DESC, ALLOWED_SORT_FIELD);
        }

        String[] tokens = sortParam.trim().split(",", -1);
        if (tokens.length > 2) {
            throw new IntegrationException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }

        String field = tokens[0].trim();
        if (!ALLOWED_SORT_FIELD.equals(field)) {
            throw new IntegrationException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }

        if (tokens.length == 1 || tokens[1].isBlank()) {
            return Sort.by(Sort.Direction.DESC, ALLOWED_SORT_FIELD);
        }

        String directionStr = tokens[1].trim().toLowerCase(Locale.ROOT);
        if ("desc".equals(directionStr)) {
            return Sort.by(Sort.Direction.DESC, ALLOWED_SORT_FIELD);
        }
        if ("asc".equals(directionStr)) {
            return Sort.by(Sort.Direction.ASC, ALLOWED_SORT_FIELD);
        }
        throw new IntegrationException(CommonErrorCode.COMMON_INVALID_REQUEST);
    }

    private GithubSyncJobStatus parseStatusFilter(String statusParam) {
        if (statusParam == null || statusParam.isBlank()) {
            return null;
        }
        try {
            return GithubSyncJobStatus.valueOf(statusParam.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IntegrationException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
    }

    private Long parsePositiveLong(String value, ErrorCode errorCode) {
        if (value == null || value.isBlank()) {
            throw new IntegrationException(errorCode);
        }
        try {
            long parsed = Long.parseLong(value.trim());
            if (parsed <= 0) {
                throw new IntegrationException(errorCode);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IntegrationException(errorCode);
        }
    }
}
