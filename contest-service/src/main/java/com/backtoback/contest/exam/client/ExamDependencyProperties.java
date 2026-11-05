package com.backtoback.contest.exam.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/** 시험 설정에 필요한 내부 서비스 주소다. */
@ConfigurationProperties(prefix = "contest.exam-dependencies")
@Validated
public record ExamDependencyProperties(@NotBlank String studyBaseUrl, @NotBlank String problemBaseUrl) {
}
