package com.backtoback.problem.global.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

/** 문제 서비스의 예약 작업 실행을 활성화한다. */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulingConfig {
    /**
     * 서비스 DB의 shedlock 테이블을 사용하는 스케줄러 잠금 공급자를 구성한다.
     * 잠금은 DB 시각을 사용하고, 업무 데이터의 만료 판단에는 기존 애플리케이션 Clock을 사용한다.
     *
     * @param jdbcTemplate 서비스 DB에 연결된 JDBC 클라이언트
     * @return 다중 인스턴스에서 같은 작업의 동시 실행을 제한하는 잠금 공급자
     */
    @Bean
    public LockProvider schedulerLockProvider(JdbcTemplate jdbcTemplate) {
        // 서버별 시계 차이로 잠금 만료 시각이 달라지지 않도록 DB 시각으로 선점·해제한다.
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration.builder().withJdbcTemplate(jdbcTemplate).usingDbTime().build()
        );
    }
}
