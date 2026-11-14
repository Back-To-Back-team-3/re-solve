package com.backtoback.study.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

/** 스터디 서비스의 예약 작업 실행을 활성화한다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulingConfig {

    /**
     * 서비스 DB의 shedlock 테이블을 사용하는 스케줄러 잠금 공급자를 구성한다.
     * 잠금은 DB 시각을 기준으로 선점·해제한다(스케줄러 명세서 §0.1).
     *
     * @param jdbcTemplate 서비스 DB에 연결된 JDBC 클라이언트
     * @return 다중 인스턴스에서 같은 작업의 동시 실행을 제한하는 잠금 공급자
     */
    @Bean
    public LockProvider schedulerLockProvider(JdbcTemplate jdbcTemplate) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder().withJdbcTemplate(jdbcTemplate).usingDbTime().build()
        );
    }
}
