package com.backtoback.member.skill.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * 진단 Run이 호출할 내부 Judge 주소를 구성한다. 별도 환경변수 없이 서비스 기본 주소를 사용한다.
 *
 * @param judgeServiceUrl Judge 내부 API 주소
 */
@Validated
@ConfigurationProperties("resolve.diagnosis-run")
public record DiagnosisRunProperties(@DefaultValue("http://judge-service:8080") @NotBlank String judgeServiceUrl) {
}
