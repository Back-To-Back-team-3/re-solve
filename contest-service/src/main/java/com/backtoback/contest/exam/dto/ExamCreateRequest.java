package com.backtoback.contest.exam.dto;

import java.time.OffsetDateTime;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 스터디 시험 생성에 필요한 전체 설정이다 (API 명세서 §4.1.1). */
public record ExamCreateRequest(
    @NotBlank(message = "제목을 입력해 주세요.")
    @Size(
        max = 200,
        message = "제목은 200자 이하여야 합니다."
    ) String title,
    @NotNull(message = "시험 방식을 입력해 주세요.")
    @Pattern(
        regexp = "FIXED",
        message = "FIXED 방식만 허용합니다."
    ) String mode,
    @NotNull(message = "시작 시각을 입력해 주세요.") OffsetDateTime startsAt,
    @NotNull(message = "종료 시각을 입력해 주세요.") OffsetDateTime endsAt,
    @NotNull(message = "문제를 선택해 주세요.")
    @Size(
        min = 1,
        max = 20,
        message = "문제는 1개 이상 20개 이하여야 합니다."
    ) List<@NotNull @Valid Problem> problems
) {
    /** 시험에 배치할 문제와 배점이다. */
    public record Problem(
        @NotBlank(message = "문제 ID를 입력해 주세요.")
        @Pattern(
            regexp = "[1-9][0-9]*",
            message = "올바른 문제 ID를 입력해 주세요."
        ) String problemId,
        @NotNull(message = "배점을 입력해 주세요.")
        @Pattern(
            regexp = "(?:0|[1-9][0-9]{0,3})\\.[0-9]{2}",
            message = "배점은 소수점 둘째 자리까지 입력해 주세요."
        ) String score,
        @NotNull(message = "표시 순서를 입력해 주세요.") Integer displayOrder
    ) {
    }
}
