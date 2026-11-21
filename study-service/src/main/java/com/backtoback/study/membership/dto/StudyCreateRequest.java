package com.backtoback.study.membership.dto;

import com.backtoback.study.membership.domain.JoinType;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 스터디 생성 요청 바디 (API 명세서 §5.2.1 요청 바디).
 * {@code skillLevel}은 {@code LV1}~{@code LV3}만 받는다(정책 및 상태 §7.1 허용 수준).
 * {@code goal}·{@code activeTimeSlot}은 학습 프로필과 같은 값 목록만 받는다(API 명세서 §5.2.1, §1.3.2 학습 프로필 저장).
 * 추천의 목표 일치·시간대 비교가 같은 값 체계를 전제로 한다.
 */
public record StudyCreateRequest(
    @NotBlank @Size(max = 200) String name,
    @Size(max = 500) String description,
    @NotBlank
    @Pattern(
        regexp = "^(EMPLOYMENT|CONTEST|STUDY)$",
        message = "goal은 EMPLOYMENT, CONTEST, STUDY 중 하나여야 합니다"
    ) String goal,
    @NotBlank
    @Pattern(
        regexp = "^LV[1-3]$",
        message = "skillLevel은 LV1~LV3 중 하나여야 합니다"
    ) String skillLevel,
    @NotBlank @Size(max = 30) String preferredLanguage,
    @NotBlank
    @Pattern(
        regexp = "^(MORNING|AFTERNOON|EVENING|NIGHT)$",
        message = "activeTimeSlot은 MORNING, AFTERNOON, EVENING, NIGHT 중 하나여야 합니다"
    ) String activeTimeSlot,
    @NotNull @Min(2) @Max(10) Integer capacity,
    @NotNull JoinType joinType,
    @NotNull Boolean isAutoReopen
) {
}
