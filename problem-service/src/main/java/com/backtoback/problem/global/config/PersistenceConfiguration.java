package com.backtoback.problem.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.backtoback.common.jpa.config.JpaAuditingConfiguration;

/**
 * 문제 서비스의 생성·수정 시각을 공통 JPA 감사 설정으로 기록한다.
 */
@Configuration(proxyBeanMethods = false)
@Import(JpaAuditingConfiguration.class)
public class PersistenceConfiguration {
    // MVC 슬라이스 테스트와 분리하여 JPA 컨텍스트에서만 감사 설정을 활성화한다.
}
