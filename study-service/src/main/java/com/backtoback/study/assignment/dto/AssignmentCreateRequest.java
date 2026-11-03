package com.backtoback.study.assignment.dto;

import java.time.LocalDateTime;
import java.util.List;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 문제집 생성 요청 바디 (API 명세서 §5.4.1).
 */
public record AssignmentCreateRequest(
    @NotBlank @Size(max = 200) String title,
    @Size(max = 500) String goal,
    Long tagId,
    @NotEmpty @Size(max = 20) List<Long> problemIds,
    Boolean isPreSolvedAllowed,
    @NotNull LocalDateTime startsAt,
    @NotNull LocalDateTime deadlineAt
) {

    @AssertTrue(message = "problemIds에 중복이 있습니다")
    public boolean isProblemIdsDistinct() {
        if (problemIds == null) {
            return true;
        }
        return problemIds.stream().distinct().count() == problemIds.size();
    }

    @AssertTrue(message = "deadlineAt은 startsAt 이후여야 합니다")
    public boolean isDeadlineAfterStart() {
        if (startsAt == null || deadlineAt == null) {
            return true;
        }
        return deadlineAt.isAfter(startsAt);
    }
}
