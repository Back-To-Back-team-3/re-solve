package com.backtoback.member.member.dto;

import java.util.List;

import com.backtoback.member.member.domain.ActiveTimeSlot;
import com.backtoback.member.member.domain.LearningGoal;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

/**
 * 학습 프로필 저장 요청 (API 명세서 1.3.2). 목록에 없는 목표·시간대 값은 본문을 읽지 못해 {@code COMMON_INVALID_REQUEST}가 된다.
 */
public record LearningProfileRequest(
    @NotNull(message = "학습 목표는 필수입니다.") LearningGoal goal,
    @NotEmpty(message = "선호 언어를 1개 이상 선택해 주세요.") List<@NotNull @Pattern(
        regexp = "java|python3|cpp",
        message = "선호 언어는 java, python3, cpp 중 하나입니다."
    ) String> preferredLanguages,
    @NotEmpty(message = "활동 시간대를 1개 이상 선택해 주세요.") List<@NotNull ActiveTimeSlot> activeTimeSlots
) {

}
