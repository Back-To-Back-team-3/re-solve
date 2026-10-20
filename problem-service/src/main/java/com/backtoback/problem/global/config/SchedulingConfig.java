package com.backtoback.problem.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {
    // 스케줄러와 JPA 감사는 공통 Clock을 사용한다.
}
