package com.backtoback.judge.submission.dto;

import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.submission.domain.Submission.Context;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/** 일반 풀이 목록 필터. 문맥별 결과 공개 연동 전에는 PRACTICE만 조회한다. */
public record SubmissionListRequest(
    @Pattern(
        regexp = "[1-9][0-9]{0,18}",
        message = "문제 ID는 양수여야 합니다."
    ) String problemId,
    Context context,
    JudgeVerdict verdict,
    @Min(
        value = 0,
        message = "페이지는 0 이상이어야 합니다."
    ) Integer page,
    @Min(
        value = 1,
        message = "페이지 크기는 1 이상이어야 합니다."
    )
    @Max(
        value = 100,
        message = "페이지 크기는 100 이하여야 합니다."
    ) Integer size,
    @Pattern(
        regexp = "submittedAt,(asc|desc)",
        message = "허용 정렬은 submittedAt,asc 또는 submittedAt,desc입니다."
    ) String sort
) {
    public SubmissionListRequest {
        page = page == null ? 0 : page;
        size = size == null ? 20 : size;
        sort = sort == null ? "submittedAt,desc" : sort;
    }

    @jakarta.validation.constraints.AssertTrue(message = "사용자 판정만 필터로 사용할 수 있습니다.")
    public boolean isUserVerdict() {
        return verdict != JudgeVerdict.SYSTEM_ERROR;
    }
}
