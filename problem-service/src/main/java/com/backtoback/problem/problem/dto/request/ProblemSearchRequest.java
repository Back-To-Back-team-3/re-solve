package com.backtoback.problem.problem.dto.request;

import java.util.List;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.exception.ProblemException;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 일반 공개 문제 검색 조건(API 명세서 §2.1.1). */
public record ProblemSearchRequest(
    String keyword,
    @Min(
        value = 1,
        message = "난이도는 1 이상이어야 합니다."
    )
    @Max(
        value = 3,
        message = "난이도는 3 이하여야 합니다."
    ) Integer difficulty,
    List<Long> tagIds,
    String solvedStatus,
    Integer page,
    Integer size,
    String sort
) {
    /**
     * 기본값을 적용하고 검색 범위를 검증한다.
     *
     * @return 정규화 검색 조건
     * @throws ProblemException 잘못된 검색 조건
     */
    public ProblemSearchRequest normalized() {
        int number = page == null ? 0 : page;
        int limit = size == null ? 20 : size;
        String order = sort == null ? "publishedAt,desc" : sort;
        if (number < 0 || limit < 1 || limit > 100 || (long)number * limit > Integer.MAX_VALUE
            || difficulty != null && (difficulty < 1 || difficulty > 3)
            || tagIds != null && tagIds.stream().anyMatch(id -> id == null || id <= 0)
            || solvedStatus != null && !List.of("SOLVED", "UNSOLVED").contains(solvedStatus)
            || !order.matches("(publishedAt|difficulty|problemId),(asc|desc)")) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        return new ProblemSearchRequest(keyword, difficulty, tagIds, solvedStatus, number, limit, order);
    }
}
