package com.backtoback.judge.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.backtoback.common.jpa.config.JpaAuditingConfiguration;

/** 채점 저장 모델에 공통 {@code Clock}과 JPA 감사 시각을 등록한다. */
@Configuration(proxyBeanMethods = false)
@Import(JpaAuditingConfiguration.class)
public class PersistenceConfiguration {
    // 감사 리스너와 Clock은 공통 설정에서만 등록한다.
}
