package com.backtoback.problem.problem.dto.request;

import java.util.List;

import com.backtoback.problem.problem.domain.ProblemScope;
import com.backtoback.problem.problem.dto.FunctionSpec;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** 최초 문제 등록의 원래 입력. 기본값 적용 전 값을 멱등 해시에 사용한다. */
public record ProblemCreateRequest(
    @NotNull(message = "문제 범위는 필수입니다.") ProblemScope scope,
    @NotBlank(message = "제목은 필수입니다.")
    @Size(
        max = 200,
        message = "제목은 200자 이하여야 합니다."
    ) String title,
    @NotNull(message = "문제 설명은 필수입니다.") String description,
    @NotNull(message = "제한 사항은 필수입니다.") String constraints,
    @NotNull(message = "입출력 예시는 필수입니다.") String examples,
    @NotNull(message = "난이도는 필수입니다.")
    @Min(
        value = 1,
        message = "난이도는 1 이상이어야 합니다."
    )
    @Max(
        value = 3,
        message = "난이도는 3 이하여야 합니다."
    ) Integer difficulty,
    @NotNull(message = "태그 목록은 필수입니다.") List<String> tagIds,
    FunctionSpec functionSpec,
    @Valid Limits executionLimits,
    List<TestCaseRequest> testCases
) {
    /** 등록 시 회차에 고정할 실행 제한의 선택 입력. */
    public record Limits(
        @Min(
            value = 1000,
            message = "CPU 시간 제한은 1000ms 이상이어야 합니다."
        ) Integer timeMs,
        @Positive(message = "메모리 제한은 양수여야 합니다.")
        @Max(
            value = 512000,
            message = "메모리 제한은 512000KB 이하여야 합니다."
        ) Integer memoryKb,
        @Positive(message = "벽시계 시간 제한은 양수여야 합니다.") Integer wallTimeMs,
        @Min(
            value = 1,
            message = "반환값 제한은 1KiB 이상이어야 합니다."
        )
        @Max(
            value = 256,
            message = "반환값 제한은 256KiB 이하여야 합니다."
        ) Integer outputLimitKb
    ) {
    }
}
