package com.backtoback.member.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.backtoback.common.jpa.config.JpaAuditingConfiguration;

/**
 * 공통 JPA 감사 설정과 Clock을 등록한다. {@code @WebMvcTest}가 JPA 없이 뜨도록 애플리케이션 클래스가 아닌 별도 설정에 둔다.
 */
@Configuration(proxyBeanMethods = false)
@Import(JpaAuditingConfiguration.class)
public class JpaConfig {

}
