package com.backtoback.problem.global.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** 문제 서비스의 예약 작업 실행을 활성화한다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class SchedulingConfig {

}
