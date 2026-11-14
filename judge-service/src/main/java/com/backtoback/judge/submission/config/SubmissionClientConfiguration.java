package com.backtoback.judge.submission.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 접수 내부 클라이언트의 설정을 등록한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SubmissionClientProperties.class)
public class SubmissionClientConfiguration {
    // 접수 설정은 이 구성에서만 등록한다.
}
