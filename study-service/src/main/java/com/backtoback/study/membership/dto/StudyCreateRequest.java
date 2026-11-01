package com.backtoback.study.membership.dto;

import com.backtoback.study.membership.domain.JoinType;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * 스터디 생성 요청 바디 (API 명세서 §5.2.1 요청 바디).
 */
public record StudyCreateRequest(
    @NotBlank @Size(max = 200) String name,
    @Size(max = 500) String description,
    @NotBlank @Size(max = 30) String goal,
    @NotBlank @Size(max = 30) String skillLevel,
    @NotBlank @Size(max = 30) String preferredLanguage,
    @NotBlank @Size(max = 30) String activeTimeSlot,
    @NotNull @Min(2) @Max(10) Integer capacity,
    @NotNull JoinType joinType,
    @NotNull Boolean isAutoReopen
) {
}
