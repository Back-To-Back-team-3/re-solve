package com.backtoback.member.skill.config;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import com.backtoback.member.skill.domain.TagLevel;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;

/**
 * 태그 레벨·진단 설정 (정책 및 상태 8.3).
 *
 * @param problemServiceUrl 태그 존재 확인(API 명세서 2.6.11)을 호출할 problem-service 주소
 * @param diagnosisCooldown 진단 종료 후 재응시까지 기다리는 시간
 * @param promotions 현재 레벨별 다음 승급 기준. 기준값은 실운영 결과를 보고 조정한다.
 */
@Validated
@ConfigurationProperties(prefix = "resolve.skill")
public record SkillProperties(
    @NotBlank String problemServiceUrl,
    @NotNull Duration diagnosisCooldown,
    @NotEmpty Map<TagLevel, @Valid Promotion> promotions
) {

    public record Promotion(
        @NotNull TagLevel targetLevel,
        int requiredSolvedCount,
        @NotNull BigDecimal requiredAchievementRate
    ) {
    }
}
