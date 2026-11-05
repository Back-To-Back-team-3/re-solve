package com.backtoback.contest.exam.dto;

import java.time.OffsetDateTime;
import java.util.List;

import com.backtoback.contest.exam.dto.ExamCreateRequest.Problem;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 시작 전 시험의 전체 설정과 조회한 버전이다 (API 명세서 §4.1.4). */
public record ExamUpdateRequest(
    @NotNull(message = "버전을 입력해 주세요.")
    @Pattern(
        regexp = "0|[1-9][0-9]*",
        message = "올바른 버전을 입력해 주세요."
    ) String version,
    @NotBlank(message = "제목을 입력해 주세요.")
    @Size(
        max = 200,
        message = "제목은 200자 이하여야 합니다."
    ) String title,
    @NotNull(message = "시작 시각을 입력해 주세요.") OffsetDateTime startsAt,
    @NotNull(message = "종료 시각을 입력해 주세요.") OffsetDateTime endsAt,
    @NotNull(message = "문제를 선택해 주세요.")
    @Size(
        min = 1,
        max = 20,
        message = "문제는 1개 이상 20개 이하여야 합니다."
    ) List<@NotNull @Valid Problem> problems
) {
    /** 생성과 같은 범위 검증에 사용할 FIXED 설정을 만든다. */
    public ExamCreateRequest settings() {
        return new ExamCreateRequest(title, "FIXED", startsAt, endsAt, problems);
    }
}
