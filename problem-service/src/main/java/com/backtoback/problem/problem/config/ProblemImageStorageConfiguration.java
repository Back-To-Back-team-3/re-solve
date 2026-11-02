package com.backtoback.problem.problem.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 문제 이미지 저장 설정을 등록한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProblemImageStorageProperties.class)
public class ProblemImageStorageConfiguration {

}
