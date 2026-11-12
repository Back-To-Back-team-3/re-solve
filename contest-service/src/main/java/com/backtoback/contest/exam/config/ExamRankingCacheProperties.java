package com.backtoback.contest.exam.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;

/** 시험 순위 캐시의 만료 시간과 확정 후 정기 복구 기간. */
@Validated
@ConfigurationProperties(prefix = "contest.ranking-cache")
public record ExamRankingCacheProperties(
    @DefaultValue("3600") @Min(1) long ttlSeconds,
    @DefaultValue("7") @Min(1) int finalizedRetentionDays
) {
}
