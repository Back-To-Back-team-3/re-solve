package com.backtoback.judge.submission.dto;

import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.submission.domain.Submission.Context;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;

/** 내 일반·시험·진단 제출 목록의 조회 조건. */
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

    /**
     * 사용자에게 공개하지 않는 시스템 오류 판정의 필터 사용을 차단한다.
     *
     * @return 사용자 판정이거나 판정 필터가 없는지 여부
     */
    @jakarta.validation.constraints.AssertTrue(message = "사용자 판정만 필터로 사용할 수 있습니다.")
    public boolean isUserVerdict() {
        return verdict != JudgeVerdict.SYSTEM_ERROR;
    }
}
