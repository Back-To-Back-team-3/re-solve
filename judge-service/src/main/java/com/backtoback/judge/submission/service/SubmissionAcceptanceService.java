package com.backtoback.judge.submission.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.domain.JudgeLanguage;
import com.backtoback.judge.submission.client.SubmissionDependencyClient;
import com.backtoback.judge.submission.dto.ProblemExecutionProfileResponse;
import com.backtoback.judge.submission.dto.ProblemValidationResponse;
import com.backtoback.judge.submission.dto.SubmissionCreateRequest;
import com.backtoback.judge.submission.dto.SubmissionCreateResponse;
import com.backtoback.judge.submission.service.dto.SubmissionStorageRequest.TestCase;
import com.backtoback.judge.submission.store.SubmissionRateLimiter;

import lombok.RequiredArgsConstructor;

/** 외부 검증을 DB 트랜잭션 밖에서 조합하고 일반 제출을 접수한다. (API 명세서 §3.1.2) */
@Service
@RequiredArgsConstructor
public class SubmissionAcceptanceService {
    private final SubmissionAcceptanceStore store;
    private final SubmissionDependencyClient dependencies;
    private final SubmissionAccessService access;
    private final SubmissionRateLimiter rateLimiter;
    private final Clock clock;

    /**
     * 원래 멱등 응답을 우선 반환하고 신규 요청만 검증하여 원자적으로 저장한다.
     * 트랜잭션은 저장 모듈에만 두어 원격 호출 동안 DB 잠금을 보유하지 않는다.
     *
     * @param memberId 인증 회원 ID
     * @param key 멱등 키
     * @param request 사용자 제출 원본
     * @return 최초 202 응답
     * @throws JudgeException {@code COMMON_INVALID_REQUEST}, {@code CODE_TOO_LARGE}, {@code UNSUPPORTED_LANGUAGE}:
     *     요청 형식·코드 크기·지원 언어 오류인 경우.
     *     {@code IDEMPOTENCY_KEY_CONFLICT}, {@code SUBMISSION_RATE_LIMITED}: 멱등 충돌 또는 요청 한도 초과인 경우.
     *     {@code MEMBER_SUSPENDED}, {@code AUTH_TOKEN_INVALID}, {@code PROBLEM_NOT_AVAILABLE},
     *     {@code LEVEL_PROBLEM_LOCKED}: 회원·문제 접근 조건을 충족하지 않는 경우.
     *     {@code COMMON_DEPENDENCY_UNAVAILABLE}, {@code SUBMISSION_RUNTIME_UNAVAILABLE} 또는 Judge0 오류:
     *     외부 검증이나 고정 런타임 가용성 확인이 실패한 경우
     */
    public SubmissionCreateResponse create(long memberId, String key, SubmissionCreateRequest request) {
        if (memberId <= 0 || request == null) {
            throw new JudgeException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        request.validate(key);
        String hash = request.requestHash();
        var existing = store.findReusable(memberId, key, hash);
        if (existing.isPresent()) {
            return existing.get();
        }
        LocalDateTime submittedAt
            = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);
        JudgeLanguage.fromCode(request.language());
        access.checkMember(memberId);
        long problemId = SubmissionCreateRequest.parseId(request.problemId());
        long revisionId = SubmissionCreateRequest.parseId(request.problemRevisionId());
        try {
            rateLimiter.acquire(memberId, problemId);
        } catch (JudgeException exception) {
            if (exception.getErrorCode() == JudgeErrorCode.SUBMISSION_RATE_LIMITED) {
                // 최초 조회 이후 다른 요청이 접수를 완료했을 수 있으므로 새 읽기로 응답을 복원한다.
                var replay = store.findReusable(memberId, key, hash);
                if (replay.isPresent()) {
                    return replay.get();
                }
            }
            throw exception;
        }
        var validation = access.checkProblem(memberId, problemId, revisionId);
        Map<String, ProblemExecutionProfileResponse> profiles
            = access.profiles(validation.supportedLanguages(), validation.executionProfiles());
        var selected = profiles.get(request.language());
        if (selected == null) {
            throw new JudgeException(JudgeErrorCode.UNSUPPORTED_LANGUAGE);
        }
        var tests = tests(validation, request, profiles);
        var runtime = access.runtime(request.language(), selected);
        try {
            return store.accept(memberId, key, hash, request, submittedAt, runtime, tests);
        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException exception) {
            // 실패한 저장 트랜잭션이 종료된 뒤 새 조회에서 동시 승자의 응답을 복원한다.
            return store.findReusable(memberId, key, hash).orElseThrow(() -> exception);
        }
    }

    /**
     * 사전 검증과 고정 자료의 동일성을 확인하고 테스트 메타데이터만 전달한다.
     *
     * @param value 사전 검증 응답
     * @param request 제출 원본
     * @param profiles 사전 검증 프로필
     * @return 초기 결과 자리 메타데이터
     * @throws JudgeException {@code COMMON_DEPENDENCY_UNAVAILABLE}: 사전 검증과 고정 자료가 일치하지 않는 경우
     */
    private List<TestCase> tests(
        ProblemValidationResponse value,
        SubmissionCreateRequest request,
        Map<String, ProblemExecutionProfileResponse> profiles
    ) {
        var data
            = dependencies
                .data(
                    SubmissionCreateRequest.parseId(request.problemId()),
                    SubmissionCreateRequest.parseId(request.problemRevisionId())
                );
        require(
            data != null && request.problemId().equals(data.problemId())
                && request.problemRevisionId().equals(data.problemRevisionId()) && data.cases() != null
                && data.cases().size() == value.totalCount()
                && profiles.equals(access.profiles(data.supportedLanguages(), data.executionProfiles()))
        );
        Set<Long> expected = new HashSet<>();
        for (String id : value.testAssetIds()) {
            expected.add(internalId(id));
        }
        Set<Integer> orders = new HashSet<>();
        Set<Long> seen = new HashSet<>();
        List<TestCase> result = new ArrayList<>();
        for (var test : data.cases()) {
            require(
                test != null && test.testOrder() != null && test.testOrder() > 0 && orders.add(test.testOrder())
                    && test.isSample() != null
            );
            long id = internalId(test.testAssetId());
            require(expected.contains(id) && seen.add(id));
            result.add(new TestCase(id, test.testOrder(), test.isSample()));
        }
        require(seen.equals(expected));
        return List.copyOf(result);
    }

    /**
     * 내부 응답 ID 오류를 사용자 입력 오류로 바꾸지 않고 검증 실패로 전달한다.
     *
     * @param id 내부 응답 ID
     * @return 양수 ID
     * @throws JudgeException {@code COMMON_DEPENDENCY_UNAVAILABLE}: 내부 응답의 식별자가 유효하지 않은 경우
     */
    private long internalId(String id) {
        try {
            return SubmissionCreateRequest.parseId(id);
        } catch (JudgeException exception) {
            throw new JudgeException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
    }

    /**
     * 불완전한 내부 응답을 fail-closed로 거절한다.
     *
     * @param condition 계약 충족 여부
     * @throws JudgeException {@code COMMON_DEPENDENCY_UNAVAILABLE}: 내부 응답이 계약을 충족하지 않는 경우
     */
    private void require(boolean condition) {
        if (!condition) {
            throw new JudgeException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
        }
    }
}
