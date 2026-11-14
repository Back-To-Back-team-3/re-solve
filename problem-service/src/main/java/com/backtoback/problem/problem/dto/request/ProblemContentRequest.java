package com.backtoback.problem.problem.dto.request;

import java.util.List;

import com.backtoback.problem.problem.dto.FunctionSpec;

/** 최초 등록과 새 회차 등록에서 동일하게 검증하는 전체 문제 입력. */
public interface ProblemContentRequest {
    /**
     * 등록 입력의 {@code title} 값을 조회한다.
     *
     * @return 기본값 적용 전 입력
     */
    String title();

    /**
     * 등록 입력의 {@code description} 값을 조회한다.
     *
     * @return 기본값 적용 전 입력
     */
    String description();

    /**
     * 등록 입력의 {@code constraints} 값을 조회한다.
     *
     * @return 기본값 적용 전 입력
     */
    String constraints();

    /**
     * 등록 입력의 {@code examples} 값을 조회한다.
     *
     * @return 기본값 적용 전 입력
     */
    String examples();

    /**
     * 등록 입력의 {@code difficulty} 값을 조회한다.
     *
     * @return 기본값 적용 전 입력
     */
    Integer difficulty();

    /**
     * 등록 입력의 {@code tagIds} 값을 조회한다.
     *
     * @return 기본값 적용 전 입력
     */
    List<String> tagIds();

    /**
     * 등록 입력의 {@code functionSpec} 값을 조회한다.
     *
     * @return 기본값 적용 전 입력
     */
    FunctionSpec functionSpec();

    /**
     * 등록 입력의 {@code executionLimits} 값을 조회한다.
     *
     * @return 기본값 적용 전 입력
     */
    ProblemCreateRequest.Limits executionLimits();

    /**
     * 등록 입력의 {@code testCases} 값을 조회한다.
     *
     * @return 기본값 적용 전 입력
     */
    List<TestCaseRequest> testCases();

}
