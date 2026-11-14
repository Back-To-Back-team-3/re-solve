package com.backtoback.common.scheduling.config;

import javax.sql.DataSource;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;

/**
 * 서비스가 명시적으로 등록한 경우 서비스 DB의 shedlock 테이블에 연결한 잠금 공급자를 제공한다.
 * 예약 실행과 정리 작업은 별도 설정에서 등록하고 테이블 생성·Flyway 실행은 서비스에 맡긴다.
 * <p>기준 문서</p>
 * <ul>
 * <li>스케줄러 v1.0 / §0.1 공통 규칙·§0.2 공통 스케줄러: JDBC 잠금과 최소 유지 시간 0</li>
 * <li>도메인 및 데이터베이스 v1.0 / §3.4 스케줄러 잠금: 서비스별 표준 shedlock 테이블</li>
 * <li>공통 모듈 적용 가이드 / §2.4 common-event·common-scheduling — 이벤트와 스케줄링 설계: 기존 등록 교체</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableSchedulerLock(defaultLockAtMostFor = "PT30M")
public class SchedulerLockConfiguration {
    /**
     * 기존 서비스 설정과 공통 설정이 함께 남아 잠금 공급자 선택이 모호해지는 구성을 시작 전에 차단한다.
     * Bean을 생성하기 전 선언된 반환 타입을 확인해 공급자를 조기 생성하지 않는다.
     *
     * @return 단일 잠금 공급자 등록을 확인할 후처리기
     */
    @Bean
    public static BeanFactoryPostProcessor singleSchedulerLockProvider() {
        return beanFactory -> {
            var providers = beanFactory.getBeanNamesForType(LockProvider.class, false, false);
            if (providers.length != 1) {
                throw new IllegalStateException(
                    "Common scheduling requires exactly one LockProvider; replace the existing lock configuration"
                );
            }
        };
    }

    /**
     * 서비스의 업무 DataSource를 재사용하며 인스턴스별 애플리케이션 시계 차이가 잠금에 영향을 주지 않게 한다.
     * 업무 데이터 보관기간은 별도로 주입하는 공통 Clock을 기준으로 판단한다.
     *
     * @param dataSource 서비스 스키마의 shedlock 테이블에 연결할 공급자
     * @return DB 시각으로 잠금의 선점·만료·해제를 계산할 JDBC 잠금 공급자
     */
    @Bean
    public LockProvider commonSchedulerLockProvider(DataSource dataSource) {
        return new JdbcTemplateLockProvider(
            JdbcTemplateLockProvider.Configuration
                .builder()
                .withJdbcTemplate(new JdbcTemplate(dataSource))
                .usingDbTime()
                .build()
        );
    }
}
