package com.backtoback.judge.judging.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 비공개 테스트 버킷 설정을 등록한다. S3 연결은 Spring Cloud AWS가 관리한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(TestDataProperties.class)
public class TestDataConfiguration {
    // 명시적인 설정 등록만 담당한다.
}
