package com.backtoback.common.jpa.config;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

import com.backtoback.common.time.ClockConfiguration;

/**
 * 주입받은 Clock을 사용해 JPA 생성·수정 시간을 서울 시간과 마이크로초 정밀도로 기록한다.
 * 사용하는 서비스에서 명시적으로 import하거나 컴포넌트 스캔으로 등록해야 한다.
 * 서비스의 기존 JPA 감사 설정은 이 설정과 중복 활성화하지 않는다.
 * Entity·Repository 스캔과 데이터베이스 연결 설정은 사용하는 서비스가 담당한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>도메인 및 데이터베이스 v1.0 / §5. 공통 컬럼 규칙: DATETIME(6)과 Asia/Seoul</li>
 * <li>스케줄러 v1.0 / §0.1 공통 규칙: Clock을 주입받는 시간 기준</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@Import(ClockConfiguration.class)
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaAuditingConfiguration {
    /**
     * 감사 이벤트가 발생할 때마다 Clock의 현재 시간을 서울 시간으로 변환해 제공한다.
     * Clock 자체의 시간대와 관계없이 서울 시간을 사용하고 나노초의 마이크로초 미만 부분은 절삭한다.
     *
     * @param clock 현재 시점을 제공할 서비스의 Clock
     * @return 생성·수정 시간에 사용할 마이크로초 정밀도의 시간 공급자
     */
    @Bean
    DateTimeProvider auditingDateTimeProvider(Clock clock) {
        return () -> Optional
            .of(
                LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE).truncatedTo(ChronoUnit.MICROS)
            );
    }
}
