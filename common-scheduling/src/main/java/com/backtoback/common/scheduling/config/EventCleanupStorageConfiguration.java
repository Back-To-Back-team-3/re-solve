package com.backtoback.common.scheduling.config;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.backtoback.common.event.store.JdbcEventCleanupStore;
import com.backtoback.common.scheduling.service.EventRecordCleanupService;
import com.backtoback.common.time.ClockConfiguration;

/**
 * 이벤트 정리 저장소와 건별 배치 실행을 연결하며 잠금·예약 작업은 활성화하지 않는다.
 * 서비스가 제공하는 DB·트랜잭션 관리자·Clock을 사용하고 Flyway 실행은 서비스에 맡긴다.
 * <p>기준 문서</p>
 * <ul>
 * <li>공통 모듈 적용 가이드 / §2.4 common-event·common-scheduling — 이벤트와 스케줄링 설계: 독립적 명시 등록</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
@Import(ClockConfiguration.class)
public class EventCleanupStorageConfiguration {
    /**
     * 서비스 스키마의 이벤트 기록을 정리하는 저장소를 구성한다.
     *
     * @param dataSource 서비스 업무 DB에 연결한 공급자
     * @return 호출자의 정리 트랜잭션에 참여할 저장소
     */
    @Bean
    public JdbcEventCleanupStore jdbcEventCleanupStore(DataSource dataSource) {
        return new JdbcEventCleanupStore(new JdbcTemplate(dataSource));
    }

    /**
     * 보관기간 경계와 독립 트랜잭션 배치 실행을 연결한다.
     *
     * @param cleanupStore 이벤트 정리 저장소
     * @param clock 기존 공통 Clock 또는 서비스의 우선 Clock
     * @param transactionManager 서비스 업무 DB의 트랜잭션 관리자
     * @return 예약 작업이나 명시적 호출에서 사용할 정리 실행기
     */
    @Bean
    public EventRecordCleanupService eventRecordCleanupService(
        JdbcEventCleanupStore cleanupStore,
        Clock clock,
        PlatformTransactionManager transactionManager
    ) {
        return new EventRecordCleanupService(cleanupStore, clock, transactionManager);
    }
}
