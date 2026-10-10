package com.backtoback.contest.global.config;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.auditing.DateTimeProvider;
import org.springframework.data.jpa.repository.config.EnableJpaAuditing;

/**
 * DB 시간 기준인 Asia/Seoul로 JPA 생성·갱신 시간을 기록한다.
 * 엔티티의 감사 리스너가 이 공급자를 사용하며 DB 컬럼의 정밀도에 맞춰 마이크로초 단위로 절삭한다.
 * 시험의 마감 판정이나 상태 전이 시간을 결정하는 스케줄러 구현과는 구분한다.
 * dev·exam-stub을 함께 켜는 DB 없는 계약 모드에서는 이 설정을 로딩하지 않는다.
 * <p>기준 문서:
 * <ul>
 * <li>스케줄러 v1.0 / §0.1 공통 규칙: 서비스·DB 시간대와 DATETIME(6) 정밀도</li>
 * </ul>
 */
@Profile("!dev | !exam-stub | prod | production")
@Configuration
@EnableJpaAuditing(dateTimeProviderRef = "auditingDateTimeProvider")
public class JpaConfig {

    /**
     * 감사 시간 요청마다 현재 서울 시간을 마이크로초 정밀도로 제공하는 Bean을 만든다.
     * Bean 생성 시 시간을 고정하지 않고 리스너 호출 시마다 새 시간을 계산한다.
     *
     * @return JPA 생성·갱신 시간 기록에 사용할 시간 공급자
     */
    @Bean
    public DateTimeProvider auditingDateTimeProvider() {
        return () -> Optional.of(LocalDateTime.now(ZoneId.of("Asia/Seoul")).truncatedTo(ChronoUnit.MICROS));
    }
}
