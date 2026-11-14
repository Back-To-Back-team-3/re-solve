package com.backtoback.common.event.config;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.backtoback.common.event.service.EventJsonCodec;
import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.event.store.JdbcOutboxStore;
import com.backtoback.common.time.ClockConfiguration;

import tools.jackson.databind.ObjectMapper;

/**
 * 서비스가 명시적으로 등록한 경우에만 공통 직렬화와 Outbox 저장 기능을 연결한다.
 * {@code DataSource}·{@code ObjectMapper}·트랜잭션 관리자는 서비스가 제공하며 Relay와 예약 작업은 활성화하지 않는다.
 * <p>기준 문서</p>
 * <ul>
 * <li>공통 모듈 적용 가이드 / §2.4 common-event·common-scheduling — 이벤트와 스케줄링 설계: 명시적 등록</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
@Import(ClockConfiguration.class)
public class OutboxStorageConfiguration {
    /**
     * 서비스의 JSON 설정을 유지하는 공통 이벤트 직렬화기를 구성한다.
     *
     * @param objectMapper 서비스가 제공하는 JSON 매퍼
     * @return 공통 헤더를 합치는 직렬화기
     */
    @Bean
    public EventJsonCodec eventJsonCodec(ObjectMapper objectMapper) {
        return new EventJsonCodec(objectMapper);
    }

    /**
     * 업무 트랜잭션과 같은 DataSource를 사용하도록 Outbox 저장소를 구성한다.
     *
     * @param dataSource 서비스 업무 DB의 연결 공급자
     * @return 서비스 스키마에서만 동작하는 저장소
     */
    @Bean
    public JdbcOutboxStore jdbcOutboxStore(DataSource dataSource) {
        return new JdbcOutboxStore(new JdbcTemplate(dataSource));
    }

    /**
     * 트랜잭션 프록시를 통해 업무 변경과 Outbox 저장을 같은 경계로 연결한다.
     *
     * @param outboxStore 업무 DB에 연결한 저장소
     * @param eventJsonCodec 서비스 JSON 설정을 사용하는 직렬화기
     * @param clock 기존 공통 Clock 또는 서비스가 우선 지정한 Clock
     * @return 업무 트랜잭션을 요구하는 이벤트 기록기
     */
    @Bean
    public OutboxEventWriter outboxEventWriter(
        JdbcOutboxStore outboxStore,
        EventJsonCodec eventJsonCodec,
        Clock clock
    ) {
        return new OutboxEventWriter(outboxStore, eventJsonCodec, clock);
    }
}
