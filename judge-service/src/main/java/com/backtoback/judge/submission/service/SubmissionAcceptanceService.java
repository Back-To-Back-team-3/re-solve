package com.backtoback.judge.submission.service;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.global.exception.custom.AuthTokenInvalidException;
import com.backtoback.judge.global.exception.custom.CodeTooLargeException;
import com.backtoback.judge.global.exception.custom.CommonDependencyUnavailableException;
import com.backtoback.judge.global.exception.custom.CommonInvalidRequestException;
import com.backtoback.judge.global.exception.custom.IdempotencyKeyConflictException;
import com.backtoback.judge.global.exception.custom.LevelProblemLockedException;
import com.backtoback.judge.global.exception.custom.MemberSuspendedException;
import com.backtoback.judge.global.exception.custom.ProblemNotAvailableException;
import com.backtoback.judge.global.exception.custom.SubmissionRateLimitedException;
import com.backtoback.judge.global.exception.custom.SubmissionRuntimeUnavailableException;
import com.backtoback.judge.global.exception.custom.UnsupportedLanguageException;
import com.backtoback.judge.judging.domain.JudgeLanguage;
import com.backtoback.judge.judging.repository.ExecutionRuntimeRepository;
import com.backtoback.judge.judging.service.JudgeLanguageService;
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
    private final ExecutionRuntimeRepository runtimes;
    private final JudgeLanguageService languages;
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
     * @throws CommonInvalidRequestException 요청자·원본·멱등 키가 유효하지 않은 경우
     * @throws CodeTooLargeException 코드가 UTF-8 64KiB를 초과한 경우
     * @throws UnsupportedLanguageException 서비스 또는 문제에서 지원하지 않는 언어인 경우
     * @throws IdempotencyKeyConflictException 같은 키의 본문이 다른 경우
     * @throws SubmissionRateLimitedException 신규 접수가 요청 한도를 초과한 경우
     * @throws MemberSuspendedException 정지 회원의 요청인 경우
     * @throws AuthTokenInvalidException 탈퇴 회원의 요청인 경우
     * @throws ProblemNotAvailableException 일반 풀이를 제출할 수 없는 문제인 경우
     * @throws LevelProblemLockedException 태그 레벨이 난이도에 미달한 경우
     * @throws CommonDependencyUnavailableException 검증 의존성 조회 실패 또는 응답 불일치인 경우
     * @throws SubmissionRuntimeUnavailableException 고정 실행 환경이 없거나 언어가 일치하지 않는 경우
     * @throws JudgeException 기존 런타임 서비스의 Judge0 가용성 확인이 실패한 경우
     */
    public SubmissionCreateResponse create(long memberId, String key, SubmissionCreateRequest request) {
        if (memberId <= 0 || request == null) {
            throw new CommonInvalidRequestException();
        }
        request.validate(key);
        String hash = request.requestHash();
        var existing = store.findReusable(memberId, key, hash);
        if (existing.isPresent()) {
            return existing.get();
        }
        LocalDateTime submittedAt
            = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS);
        try {
            JudgeLanguage.fromCode(request.language());
        } catch (JudgeException exception) {
            throw new UnsupportedLanguageException();
        }
        var status = dependencies.status(memberId);
        require(status != null && Long.toString(memberId).equals(status.memberId()) && status.status() != null);
        if ("SUSPENDED".equals(status.status())) {
            throw new MemberSuspendedException();
        }
        if ("WITHDRAWN".equals(status.status())) {
            throw new AuthTokenInvalidException();
        }
        require("ACTIVE".equals(status.status()));
        long problemId = SubmissionCreateRequest.parseId(request.problemId());
        long revisionId = SubmissionCreateRequest.parseId(request.problemRevisionId());
        try {
            rateLimiter.acquire(memberId, problemId);
        } catch (SubmissionRateLimitedException exception) {
            // 최초 조회 이후 다른 요청이 접수를 완료했을 수 있으므로 새 읽기로 응답을 복원한다.
            var replay = store.findReusable(memberId, key, hash);
            if (replay.isPresent()) {
                return replay.get();
            }
            throw exception;
        }
        var validation = dependencies.validation(problemId, revisionId);
        validateProblem(validation, request);
        checkLevels(memberId, validation);
        Map<String, ProblemExecutionProfileResponse> profiles
            = profiles(validation.supportedLanguages(), validation.executionProfiles());
        var selected = profiles.get(request.language());
        if (selected == null) {
            throw new UnsupportedLanguageException();
        }
        var tests = tests(validation, request, profiles);
        var runtime
            = runtimes
                .findSnapshot(selected.runtimeKey())
                .orElseThrow(() -> new SubmissionRuntimeUnavailableException())
                .toRuntime();
        if (!request.language().equals(runtime.language())) {
            throw new SubmissionRuntimeUnavailableException();
        }
        // 참고용 runnerVersion은 현재 생성기와 비교하지 않는다.
        try {
            runtime = languages.requireRuntime(request.language(), runtime);
        } catch (JudgeException exception) {
            if (exception.getErrorCode() == JudgeErrorCode.SUBMISSION_RUNTIME_UNAVAILABLE) {
                throw new SubmissionRuntimeUnavailableException();
            }
            throw exception;
        }
        try {
            return store.accept(memberId, key, hash, request, submittedAt, runtime, tests);
        } catch (DataIntegrityViolationException | ObjectOptimisticLockingFailureException exception) {
            // 실패한 저장 트랜잭션이 종료된 뒤 새 조회에서 동시 승자의 응답을 복원한다.
            return store.findReusable(memberId, key, hash).orElseThrow(() -> exception);
        }
    }

    /**
     * 문제·회차 소속과 일반 공개 조건 및 테스트 식별자를 확인한다.
     *
     * @param value 사전 검증 응답
     * @param request 접수 원본
     * @throws ProblemNotAvailableException 제출할 수 없거나 존재하지 않는 문제인 경우
     * @throws CommonDependencyUnavailableException 내부 조회 실패 또는 응답 계약 불일치인 경우
     */
    private void validateProblem(ProblemValidationResponse value, SubmissionCreateRequest request) {
        require(
            value != null && request.problemId().equals(value.problemId())
                && request.problemRevisionId().equals(value.problemRevisionId())
        );
        require(
            Set.of("GENERAL", "EXAM_ONLY").contains(value.scope() == null ? "" : value.scope()) && Set
                .of("PUBLIC", "PRIVATE", "ARCHIVED")
                .contains(value.visibility() == null ? "" : value.visibility())
        );
        if (!"GENERAL".equals(value.scope()) || !"PUBLIC".equals(value.visibility())) {
            throw new ProblemNotAvailableException();
        }
        require(
            value.difficulty() != null && value.difficulty() >= 1 && value.difficulty() <= 3 && value.tagIds() != null
                && !value.tagIds().isEmpty() && value.totalCount() != null && value.totalCount() > 0
                && value.testAssetIds() != null && value.totalCount() == value.testAssetIds().size()
        );
        Set<Long> ids = new HashSet<>();
        for (String id : value.testAssetIds()) {
            require(ids.add(internalId(id)));
        }
    }

    /**
     * 모든 문제 태그의 회원 레벨이 난이도를 충족하는지 검사한다.
     *
     * @param memberId 인증 회원 ID
     * @param value 고정 문제 회차
     * @throws LevelProblemLockedException 한 개 이상 태그의 회원 레벨이 난이도에 미달한 경우
     * @throws CommonDependencyUnavailableException 내부 레벨 응답이 유효하지 않은 경우
     */
    private void checkLevels(long memberId, ProblemValidationResponse value) {
        var response = dependencies.levels(memberId);
        require(
            response != null && Long.toString(memberId).equals(response.memberId()) && response.tagLevels() != null
        );
        Map<Long, Integer> levels = new HashMap<>();
        for (var entry : response.tagLevels()) {
            require(entry != null && entry.level() != null);
            int level = switch (entry.level()) {
                case "LV0" -> 0;
                case "LV1" -> 1;
                case "LV2" -> 2;
                case "LV3", "MASTER" -> 3;
                default -> -1;
            };
            require(level >= 0 && levels.putIfAbsent(internalId(entry.tagId()), level) == null);
        }
        Set<Long> tags = new HashSet<>();
        for (String tag : value.tagIds()) {
            long id = internalId(tag);
            require(tags.add(id));
            if (levels.getOrDefault(id, 0) < value.difficulty()) {
                throw new LevelProblemLockedException();
            }
        }
    }

    /**
     * 통과 언어와 일대일 대응하는 프로필을 검사한다.
     *
     * @param supported 통과 언어 목록
     * @param profiles 통과 프로필 목록
     * @return 언어별 프로필
     * @throws CommonDependencyUnavailableException 내부 프로필 응답이 유효하지 않은 경우
     */
    private Map<String, ProblemExecutionProfileResponse> profiles(
        List<String> supported,
        List<ProblemExecutionProfileResponse> profiles
    ) {
        require(supported != null && profiles != null);
        Set<String> codes = new HashSet<>();
        for (String code : supported) {
            require(code != null && Set.of("java", "python3", "cpp").contains(code) && codes.add(code));
        }
        Map<String, ProblemExecutionProfileResponse> result = new HashMap<>();
        for (var profile : profiles) {
            require(
                profile != null && profile.language() != null && codes.contains(profile.language())
                    && profile.runtimeKey() != null && profile.runtimeKey().matches("[0-9a-f]{64}")
                    && profile.runnerVersion() != null && !profile.runnerVersion().isBlank()
                    && profile.runnerVersion().length() <= 100
                    && result.putIfAbsent(profile.language(), profile) == null
            );
        }
        require(result.keySet().equals(codes));
        return result;
    }

    /**
     * 사전 검증과 고정 자료의 동일성을 확인하고 테스트 메타데이터만 전달한다.
     *
     * @param value 사전 검증 응답
     * @param request 제출 원본
     * @param profiles 사전 검증 프로필
     * @return 초기 결과 자리 메타데이터
     * @throws CommonDependencyUnavailableException 사전 검증과 고정 자료가 일치하지 않는 경우
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
                && profiles.equals(profiles(data.supportedLanguages(), data.executionProfiles()))
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
     * @throws CommonDependencyUnavailableException 내부 응답의 식별자가 유효하지 않은 경우
     */
    private long internalId(String id) {
        try {
            return SubmissionCreateRequest.parseId(id);
        } catch (CommonInvalidRequestException exception) {
            throw new CommonDependencyUnavailableException();
        }
    }

    /**
     * 불완전한 내부 응답을 fail-closed로 거절한다.
     *
     * @param condition 계약 충족 여부
     * @throws CommonDependencyUnavailableException 내부 응답이 계약을 충족하지 않는 경우
     */
    private void require(boolean condition) {
        if (!condition) {
            throw new CommonDependencyUnavailableException();
        }
    }
}
