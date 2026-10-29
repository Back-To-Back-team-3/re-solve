package com.backtoback.common.event.config;

import java.time.Clock;

import javax.sql.DataSource;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

import com.backtoback.common.event.service.EventConsumer;
import com.backtoback.common.event.service.EventHandler;
import com.backtoback.common.event.service.EventHandlerRegistry;
import com.backtoback.common.event.service.EventMessageDecoder;
import com.backtoback.common.event.store.JdbcProcessedEventStore;
import com.backtoback.common.time.ClockConfiguration;

import tools.jackson.databind.ObjectMapper;

/**
 * 서비스가 명시적으로 등록한 경우에만 소비 기록·업무 dispatch·커밋 후 ACK 연결을 제공한다.
 * 큐 리스너와 수동 ACK 설정은 서비스가 구성하며 Outbox Relay·예약 작업은 켜지 않는다.
 * <p>기준 문서</p>
 * <ul>
 * <li>공통 모듈 적용 가이드 / §2.4 common-event·common-scheduling — 이벤트와 스케줄링 설계: 독립적 소비 등록</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableTransactionManagement(proxyTargetClass = true)
@Import(ClockConfiguration.class)
public class EventConsumerConfiguration {
    /**
     * 서비스가 제공하는 JSON 매퍼로 Raw 이벤트와 SNS 봉투를 해석한다.
     *
     * @param objectMapper 서비스 JSON 설정
     * @return 공통 수신 형식 검사기
     */
    @Bean
    public EventMessageDecoder eventMessageDecoder(ObjectMapper objectMapper) {
        return new EventMessageDecoder(objectMapper);
    }

    /**
     * 소비 기록이 서비스 업무와 같은 DataSource를 사용하도록 구성한다.
     *
     * @param dataSource 서비스 업무 DB 연결 공급자
     * @return 서비스 스키마의 소비 기록 저장소
     */
    @Bean
    public JdbcProcessedEventStore jdbcProcessedEventStore(DataSource dataSource) {
        return new JdbcProcessedEventStore(new JdbcTemplate(dataSource));
    }

    /**
     * 서비스가 등록한 핸들러를 종류·버전으로 연결하고 중복 등록을 시작 시점에 차단한다.
     *
     * @param handlers 서비스 업무 핸들러
     * @return 종류·버전별 공통 dispatch 구성
     */
    @Bean
    public EventHandlerRegistry eventHandlerRegistry(ObjectProvider<EventHandler> handlers) {
        return new EventHandlerRegistry(handlers.orderedStream().toList());
    }

    /**
     * 업무 처리와 소비 기록의 커밋이 완료된 뒤 서비스 ACK를 실행하는 소비기를 구성한다.
     *
     * @param decoder SNS 봉투 해제·공통 형식 검사기
     * @param store 서비스 DB의 소비 기록 저장소
     * @param handlers 서비스별 종류·버전 처리기
     * @param clock 기존 공통 Clock 또는 서비스의 우선 Clock
     * @param transactionManager 업무 DB의 트랜잭션 관리자
     * @return 서비스 수동 ACK 리스너에서 호출할 소비기
     */
    @Bean
    public EventConsumer eventConsumer(
        EventMessageDecoder decoder,
        JdbcProcessedEventStore store,
        EventHandlerRegistry handlers,
        Clock clock,
        PlatformTransactionManager transactionManager
    ) {
        return new EventConsumer(decoder, store, handlers, clock, transactionManager);
    }
}
