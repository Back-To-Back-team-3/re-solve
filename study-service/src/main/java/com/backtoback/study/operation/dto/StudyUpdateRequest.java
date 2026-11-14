package com.backtoback.study.operation.dto;

import com.backtoback.study.membership.domain.JoinType;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 스터디 설정 수정 요청 바디 (API 명세서 §5.3.1 요청 바디). null 필드는 변경하지 않는다.
 */
public record StudyUpdateRequest(
    @Size(max = 500) String description,
    JoinType joinType,
    @Min(2) @Max(10) Integer capacity,
    Boolean isAutoReopen,
    @NotNull Long version
) {
}
