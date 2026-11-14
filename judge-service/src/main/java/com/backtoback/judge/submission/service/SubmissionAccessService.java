package com.backtoback.judge.submission.service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.repository.ExecutionRuntimeRepository;
import com.backtoback.judge.judging.service.JudgeLanguageService;
import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;
import com.backtoback.judge.submission.client.SubmissionDependencyClient;
import com.backtoback.judge.submission.dto.ProblemExecutionProfileResponse;
import com.backtoback.judge.submission.dto.ProblemValidationResponse;
import com.backtoback.judge.submission.dto.SubmissionCreateRequest;

import lombok.RequiredArgsConstructor;

/** 일반 제출과 Run에 같은 회원·문제·레벨·실행 환경 검증을 적용한다. */
@Service
@RequiredArgsConstructor
public class SubmissionAccessService {
    private final SubmissionDependencyClient dependencies;
    private final ExecutionRuntimeRepository runtimes;
    private final JudgeLanguageService languages;

    /** 현재 활동 가능한 회원인지 확인한다. */
    void checkMember(long memberId) {
        var status = dependencies.status(memberId);
        require(status != null && Long.toString(memberId).equals(status.memberId()) && status.status() != null);
        if ("SUSPENDED".equals(status.status())) {
            throw new JudgeException(JudgeErrorCode.MEMBER_SUSPENDED);
        }
        if ("WITHDRAWN".equals(status.status())) {
            throw new JudgeException(CommonErrorCode.AUTH_TOKEN_INVALID);
        }
        require("ACTIVE".equals(status.status()));
    }

    /** 일반 공개 문제와 고정 회차에 대한 회원의 접근 조건을 확인한다. */
    ProblemValidationResponse checkProblem(long memberId, long problemId, long revisionId) {
        var validation = dependencies.validation(problemId, revisionId);
        validateProblem(validation, Long.toString(problemId), Long.toString(revisionId));
        checkLevels(memberId, validation);
        return validation;
    }

    /** 검증 통과 프로필이 가리키는 실행 환경만 사용한다. */
    JudgeRuntime runtime(String language, ProblemExecutionProfileResponse selected) {
        if (selected == null) {
            throw new JudgeException(JudgeErrorCode.UNSUPPORTED_LANGUAGE);
        }
        var runtime
            = runtimes
                .findSnapshot(selected.runtimeKey())
                .orElseThrow(() -> new JudgeException(JudgeErrorCode.SUBMISSION_RUNTIME_UNAVAILABLE))
                .toRuntime();
        if (!language.equals(runtime.language())) {
            throw new JudgeException(JudgeErrorCode.SUBMISSION_RUNTIME_UNAVAILABLE);
        }
        // 참고용 runnerVersion은 현재 생성기와 비교하지 않는다.
        return languages.requireRuntime(language, runtime);
    }

    /** Run은 검증한 고정 실행 환경을 읽고 제출 기록을 만들지 않는다. */
    public JudgeRuntime runRuntime(long memberId, long problemId, long revisionId, String language) {
        checkMember(memberId);
        var validation = checkProblem(memberId, problemId, revisionId);
        return runtime(
            language,
            profiles(validation.supportedLanguages(), validation.executionProfiles()).get(language)
        );
    }

    /** 시험 문맥의 권한을 확인한 뒤 고정 회차의 실행 환경을 조회한다. */
    public JudgeRuntime examRunRuntime(long problemId, long revisionId, String language) {
        var validation = dependencies.validation(problemId, revisionId);
        require(
            validation != null && Long.toString(problemId).equals(validation.problemId())
                && Long.toString(revisionId).equals(validation.problemRevisionId())
        );
        return runtime(
            language,
            profiles(validation.supportedLanguages(), validation.executionProfiles()).get(language)
        );
    }

    /**
     * 문제·회차 소속과 일반 공개 조건 및 테스트 식별자를 확인한다.
     *
     * @param value 사전 검증 응답
     * @param problemId 문제 식별자
     * @param revisionId 고정 회차 식별자
     * @throws JudgeException {@code PROBLEM_NOT_AVAILABLE}: 제출할 수 없거나 존재하지 않는 문제인 경우;
     *     {@code COMMON_DEPENDENCY_UNAVAILABLE}: 내부 조회 실패 또는 응답 계약 불일치인 경우
     */
    private void validateProblem(ProblemValidationResponse value, String problemId, String revisionId) {
        require(value != null && problemId.equals(value.problemId()) && revisionId.equals(value.problemRevisionId()));
        require(
            Set.of("GENERAL", "EXAM_ONLY").contains(value.scope() == null ? "" : value.scope()) && Set
                .of("PUBLIC", "PRIVATE", "ARCHIVED")
                .contains(value.visibility() == null ? "" : value.visibility())
        );
        if (!"GENERAL".equals(value.scope()) || !"PUBLIC".equals(value.visibility())) {
            throw new JudgeException(JudgeErrorCode.PROBLEM_NOT_AVAILABLE);
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
     * @throws JudgeException {@code LEVEL_PROBLEM_LOCKED}: 한 개 이상 태그의 회원 레벨이 난이도에 미달한 경우;
     *     {@code COMMON_DEPENDENCY_UNAVAILABLE}: 내부 레벨 응답이 유효하지 않은 경우
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
                throw new JudgeException(JudgeErrorCode.LEVEL_PROBLEM_LOCKED);
            }
        }
    }

    /**
     * 통과 언어와 일대일 대응하는 프로필을 검사한다.
     *
     * @param supported 통과 언어 목록
     * @param profiles 통과 프로필 목록
     * @return 언어별 프로필
     * @throws JudgeException {@code COMMON_DEPENDENCY_UNAVAILABLE}: 내부 프로필 응답이 유효하지 않은 경우
     */
    Map<String, ProblemExecutionProfileResponse> profiles(
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
