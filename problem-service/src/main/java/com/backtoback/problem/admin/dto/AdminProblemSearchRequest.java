package com.backtoback.problem.admin.dto;

import java.util.List;

import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.domain.ProblemScope;
import com.backtoback.problem.problem.domain.ProblemVisibility;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

/** 관리자 목록의 회차 선택·필터·페이지 조건. */
public record AdminProblemSearchRequest(
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
    ProblemScope scope,
    ProblemVisibility visibility,
    Boolean latestOnly,
    @Min(
        value = 0,
        message = "페이지는 0 이상이어야 합니다."
    ) Integer page,
    @Min(
        value = 1,
        message = "크기는 1 이상이어야 합니다."
    )
    @Max(
        value = 100,
        message = "크기는 100 이하여야 합니다."
    ) Integer size,
    List<String> sort
) {
    /**
     * 허용 필드만 정렬하고 페이지 범위를 검증한다.
     *
     * @return 검증된 페이지 조건
     * @throws ProblemException 필터·페이지·정렬이 잘못되면 {@code COMMON_INVALID_REQUEST}
     */
    public PageRequest pageable() {
        int number = page == null ? 0 : page;
        int limit = size == null ? 20 : size;
        if (number < 0 || limit < 1 || limit > 100 || (long)number * limit > Integer.MAX_VALUE
            || difficulty != null && (difficulty < 1 || difficulty > 3)
            || tagIds != null && tagIds.stream().anyMatch(id -> id == null || id <= 0)) {
            throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
        }
        var orders = sort == null || sort.isEmpty() ? List.of("problemId,asc", "createdAt,desc") : sort;
        var sorting = orders.stream().map(value -> {
            if (value == null || !value.matches("(problemId|createdAt),(asc|desc)")) {
                throw new ProblemException(CommonErrorCode.COMMON_INVALID_REQUEST);
            }
            var parts = value.split(",");
            return new Sort.Order(Sort.Direction.fromString(parts[1]), parts[0]);
        }).toList();
        return PageRequest.of(number, limit, Sort.by(sorting).and(Sort.by("id")));
    }
}
