package com.backtoback.contest.exam.client;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/** 시험 Run을 전달하는 Judge 내부 주소. */
@Validated
@ConfigurationProperties(prefix = "contest.exam-run")
public record ExamRunProperties(@NotBlank(message = "Judge 내부 주소가 필요합니다.") String baseUrl) {
}
