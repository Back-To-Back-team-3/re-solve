package com.backtoback.judge.submission.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.judge.global.exception.custom.IdempotencyKeyConflictException;
import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;
import com.backtoback.judge.submission.domain.Submission;
import com.backtoback.judge.submission.domain.SubmissionDraft;
import com.backtoback.judge.submission.dto.SubmissionCreateRequest;
import com.backtoback.judge.submission.dto.SubmissionCreateResponse;
import com.backtoback.judge.submission.repository.SubmissionRepository;
import com.backtoback.judge.submission.service.dto.SubmissionStorageRequest;
import com.backtoback.judge.submission.service.dto.SubmissionStorageRequest.TestCase;

import lombok.RequiredArgsConstructor;

/** 외부 검증과 분리한 접수 트랜잭션에서 멱등성을 보장한다. (API 명세서 §3.1.2) */
@Service
@RequiredArgsConstructor
public class SubmissionAcceptanceStore {
    private final SubmissionRepository submissions;
    private final SubmissionStorageService storage;
    private final Clock clock;

    /**
     * 외부 조회·요청 제한 전에 유효한 기존 접수 응답을 복원한다.
     *
     * @param memberId 인증 회원 ID
     * @param key 멱등 키
     * @param hash 요청 해시
     * @return 유효한 원래 접수 응답
     * @throws IdempotencyKeyConflictException 유효한 동일 키의 다른 본문인 경우
     */
    @Transactional(readOnly = true)
    public Optional<SubmissionCreateResponse> findReusable(long memberId, String key, String hash) {
        return submissions
            .findByMemberIdAndIdempotencyKey(memberId, key)
            .flatMap(submission -> reusable(submission, hash, now()));
    }

    /**
     * 만료 키 해제와 새 제출·작업·테스트 저장을 원자적으로 처리한다.
     * 전체 처리 기한은 저장 기준 시각부터 10분이며 기존 접수의 기한은 연장하지 않는다. (정책 및 상태 §2)
     *
     * @param memberId 인증 회원 ID
     * @param key 멱등 키
     * @param hash 요청 해시
     * @param request 원본 요청
     * @param submittedAt 원래 서버 접수 시각
     * @param runtime 검증한 고정 환경
     * @param tests 고정 테스트 메타데이터
     * @return 새 접수 또는 동시 승자의 원래 응답
     * @throws org.springframework.dao.DataIntegrityViolationException 유일 키 경합 또는 저장 제약 위반으로 전체 저장이 롤백된 경우
     * @throws ObjectOptimisticLockingFailureException 다른 요청이 만료 키를 먼저 해제해 새 트랜잭션에서 재조회해야 하는 경우
     * @throws IdempotencyKeyConflictException 동시 요청의 본문이 다른 경우
     */
    @Transactional
    public SubmissionCreateResponse accept(
        long memberId,
        String key,
        String hash,
        SubmissionCreateRequest request,
        LocalDateTime submittedAt,
        JudgeRuntime runtime,
        List<TestCase> tests
    ) {
        LocalDateTime now = now();
        var previous = submissions.findByMemberIdAndIdempotencyKey(memberId, key);
        if (previous.isPresent()) {
            var existing = reusable(previous.get(), hash, now);
            if (existing.isPresent()) {
                return existing.get();
            }
            // 이전 행 PK를 조건으로 갱신하여 신규 키의 불필요한 gap lock을 피한다.
            if (submissions.releaseExpiredKey(previous.get().getId(), key, now.minusHours(24)) != 1) {
                throw new ObjectOptimisticLockingFailureException(Submission.class, previous.get().getId());
            }
        }
        var draft
            = new SubmissionDraft(
                memberId,
                SubmissionCreateRequest.parseId(request.problemId()),
                SubmissionCreateRequest.parseId(request.problemRevisionId()),
                Submission.Context.PRACTICE,
                null,
                null,
                key,
                hash,
                request.language(),
                request.sourceCode(),
                submittedAt
            );
        var saved = storage.register(new SubmissionStorageRequest(draft, now.plusMinutes(10), tests, runtime));
        return SubmissionCreateResponse.from(submissions.findById(saved.submissionId()).orElseThrow());
    }

    /**
     * 정확히 24시간이 지난 키는 새 요청 대상으로 취급한다.
     *
     * @param submission 기존 제출
     * @param hash 새 요청 해시
     * @param now 서버 시각
     * @return 유효기간 내 원래 응답
     * @throws IdempotencyKeyConflictException 같은 키의 본문이 다른 경우
     */
    private Optional<SubmissionCreateResponse> reusable(Submission submission, String hash, LocalDateTime now) {
        if (!submission.getCreatedAt().plusHours(24).isAfter(now)) {
            return Optional.empty();
        }
        if (!hash.equals(submission.getRequestHash())) {
            throw new IdempotencyKeyConflictException();
        }
        return Optional.of(SubmissionCreateResponse.from(submission));
    }

    /**
     * 서울 기준의 주입된 현재 시각을 조회한다.
     *
     * @return 현재 시각
     */
    private LocalDateTime now() {
        return LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE);
    }
}
