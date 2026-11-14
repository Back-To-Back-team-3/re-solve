package com.backtoback.study.hint.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * 힌트 기능 토글 설정 (study.hint.feature.enabled).
 * false이면 힌트 관련 모든 API에 503을 반환한다.
 */
@Validated
@ConfigurationProperties("study.hint.feature")
public record HintFeatureProperties(boolean enabled) {

}
