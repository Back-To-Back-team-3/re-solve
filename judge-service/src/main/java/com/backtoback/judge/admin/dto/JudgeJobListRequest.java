package com.backtoback.judge.admin.dto;

import com.backtoback.judge.judging.domain.JudgeJob;
import com.backtoback.judge.submission.domain.Submission;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/** 관리자 작업 목록의 허용 필터와 페이지·정렬 조건. */
public record JudgeJobListRequest(
    JudgeJob.Status status,
    Submission.Context context,
    @Pattern(
        regexp = "[1-9][0-9]{0,18}",
        message = "문맥 ID는 양수여야 합니다."
    )
    @DecimalMax(
        value = "9223372036854775807",
        message = "문맥 ID는 BIGINT 범위여야 합니다."
    ) String contextId,
    @Pattern(
        regexp = "[A-Z0-9_]{1,100}",
        message = "실패 코드는 영문 대문자·숫자·밑줄이어야 합니다."
    ) String failureCode,
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
    @Pattern(
        regexp = "(createdAt|updatedAt),(asc|desc)",
        message = "허용된 작업 시각 정렬을 사용해야 합니다."
    ) String sort
) {
    /** 생략한 페이지·크기·정렬에 API 기본값을 적용한다. */
    public JudgeJobListRequest {
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
        sort = sort == null ? "updatedAt,desc" : sort;
    }
}
