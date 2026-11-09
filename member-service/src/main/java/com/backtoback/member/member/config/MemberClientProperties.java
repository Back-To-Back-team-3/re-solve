package com.backtoback.member.member.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.NotBlank;

/**
 * 회원 탈퇴 차단 조건(API 명세서 1.2.3)을 확인할 내부 API 주소.
 *
 * @param contestServiceUrl 실제 입장·미확정 문맥 조회(4.7.2)
 * @param studyServiceUrl 스터디장 가입 조회(5.7.2)
 */
@Validated
@ConfigurationProperties(prefix = "resolve.member")
public record MemberClientProperties(@NotBlank String contestServiceUrl, @NotBlank String studyServiceUrl) {

}
