package com.backtoback.problem.problem.service;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;

import org.springframework.stereotype.Component;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.dto.request.ProblemContentRequest;
import com.backtoback.problem.problem.dto.request.ProblemCreateRequest;
import com.backtoback.problem.problem.dto.request.ProblemRevisionRequest;

import jakarta.validation.Validator;
import lombok.RequiredArgsConstructor;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** 외부 저장 전에 입력 크기·함수 계약을 검증하고 원래 요청의 멱등 해시를 계산한다. */
@Component
@RequiredArgsConstructor
public class ProblemCreateInput {
    private final Validator validator;
    private final TestCaseValidator tests;
    private final ProblemImageReferenceParser images;
    private final JsonMapper canonical
        = JsonMapper.builder().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).build();

    /**
     * 원래 본문을 보존하고 객체 키 순서만 정규화하여 업무 종류와 함께 해시한다.
     *
     * @param request 기본값 적용 전 입력
     * @return 최초 등록 전용 SHA-256 해시
     */
    public String hash(ProblemCreateRequest request) {
        return ProblemAssetContent.fromBytes(canonical.writeValueAsBytes(List.of("CREATE_PROBLEM", request))).sha256();
    }

    /**
     * 대상 문제와 원래 버전을 포함해 새 회차 요청을 해시한다.
     *
     * @param problemId 대상 문제
     * @param request 원래 전체 입력
     * @return 새 회차 등록 전용 SHA-256 해시
     */
    public String hash(long problemId, ProblemRevisionRequest request) {
        return ProblemAssetContent
            .fromBytes(canonical.writeValueAsBytes(List.of("CREATE_REVISION", problemId, request)))
            .sha256();
    }

    /**
     * 등록 입력과 UTF-8 테스트 크기를 확인하고 고정 실행 제한을 계산한다.
     *
     * @param request 등록 입력
     * @return 태그 식별자와 확정 실행 제한
     * @throws ProblemException 입력·함수·테스트·이미지 계약을 위반한 경우
     */
    public Validated validate(ProblemContentRequest request) {
        if (request == null || !validator.validate(request).isEmpty()) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        var tagIds = request.tagIds().stream().map(this::tagId).toList();
        if (new HashSet<>(tagIds).size() != tagIds.size()) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        if (request.testCases() == null || request.testCases().size() > 50) {
            throw new ProblemException(ProblemErrorCode.PROBLEM_TEST_CASE_INVALID);
        }
        long total = 0;
        for (var test : request.testCases()) {
            if (test == null || test.argumentTexts() == null || test.expectedReturnText() == null
                || test.argumentTexts().values().stream().anyMatch(value -> value == null)) {
                throw new ProblemException(ProblemErrorCode.PROBLEM_TEST_CASE_INVALID);
            }
            long size = bytes(test.expectedReturnText());
            for (String value : test.argumentTexts().values()) {
                size += bytes(value);
            }
            total += size;
            if (size > 262144 || total > 4194304) {
                throw new ProblemException(ProblemErrorCode.PROBLEM_TEST_CASE_TOO_LARGE);
            }
        }
        tests.validate(request.functionSpec(), request.testCases());
        images.parse(request.description(), request.constraints(), request.examples());
        var limits = request.executionLimits();
        int time = limits == null || limits.timeMs() == null ? 2000 : limits.timeMs();
        int memory = limits == null || limits.memoryKb() == null ? 262144 : limits.memoryKb();
        long wall = limits == null || limits.wallTimeMs() == null ? (long)time + 5000 : limits.wallTimeMs();
        int output = limits == null || limits.outputLimitKb() == null ? 64 : limits.outputLimitKb();
        if (wall > Integer.MAX_VALUE) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        return new Validated(tagIds, time, memory, (int)wall, output);
    }

    /**
     * 태그 ID를 양수 정수로 검증한다.
     *
     * @param value 외부 문자열 ID
     * @return 태그 식별자
     * @throws ProblemException 양수 정수 형식이나 범위를 벗어난 경우
     */
    private Long tagId(String value) {
        try {
            if (value == null || !value.matches("[1-9][0-9]{0,18}")) {
                throw new NumberFormatException();
            }
            return Long.parseLong(value);
        } catch (NumberFormatException failure) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
    }

    /**
     * 테스트 원문의 UTF-8 크기를 계산한다.
     *
     * @param value JSON 텍스트 원문
     * @return UTF-8 바이트 수
     */
    private int bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8).length;
    }

    /** 등록 시 확정한 태그와 실행 제한. */
    public record Validated(List<Long> tagIds, int time, int memory, int wall, int output) {
    }
}
