package com.backtoback.judge.judging.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/** Problem과 공유하는 비공개 테스트 버킷. AWS 연결은 공통 Spring Cloud AWS 설정을 사용한다. */
@Validated
@ConfigurationProperties("judge.test-data")
public record TestDataProperties(@NotBlank @DefaultValue("resolve-problem-storage") String bucket) {
}
