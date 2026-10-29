package com.backtoback.common.event.config;

import java.time.Clock;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;

import com.backtoback.common.event.client.AwsEventPublisher;
import com.backtoback.common.event.client.EventDestinationResolver;
import com.backtoback.common.event.service.OutboxPublishedCallback;
import com.backtoback.common.event.service.OutboxRelay;
import com.backtoback.common.event.store.JdbcOutboxStore;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;

/**
 * 서비스가 명시적으로 등록할 때만 Outbox 저장과 Relay 예약 실행을 활성화한다.
 * AWS 클라이언트·리소스 선택은 서비스가 제공하며 소비 기능과 ShedLock은 등록하지 않는다.
 * <p>기준 문서</p>
 * <ul>
 * <li>공통 모듈 적용 가이드 / §2.4 common-event·common-scheduling — 이벤트와 스케줄링 설계: 독립적 명시 등록</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@Import(OutboxStorageConfiguration.class)
public class OutboxRelayConfiguration {
    /**
     * 서비스가 사용하는 채널의 SDK 클라이언트만 연결하며 클라이언트를 자동 생성하지 않는다.
     *
     * @param snsClients 서비스가 제공한 선택적 SNS 클라이언트
     * @param sqsClients 서비스가 제공한 선택적 SQS 클라이언트
     * @param destinationResolver 서비스별 이벤트의 리소스 선택기
     * @return Outbox 저장 메시지를 전송하는 발행기
     */
    @Bean
    public AwsEventPublisher awsEventPublisher(
        ObjectProvider<SnsClient> snsClients,
        ObjectProvider<SqsClient> sqsClients,
        EventDestinationResolver destinationResolver
    ) {
        return new AwsEventPublisher(snsClients.getIfAvailable(), sqsClients.getIfAvailable(), destinationResolver);
    }

    /**
     * 저장소·발행기와 서비스가 등록한 업무 콜백을 트랜잭션 Relay로 연결한다.
     *
     * @param outboxStore 서비스 DB에 연결한 저장소
     * @param publisher 서비스가 구성한 SNS/SQS 발행기
     * @param callbacks 서비스가 순서를 지정할 수 있는 발행 성공 콜백
     * @param clock 기존 공통 Clock 또는 서비스의 우선 Clock
     * @param transactionManager 서비스 업무 DB의 트랜잭션 관리자
     * @return 명시적 설정 등록 후에만 예약 실행하는 Relay
     */
    @Bean
    public OutboxRelay outboxRelay(
        JdbcOutboxStore outboxStore,
        AwsEventPublisher publisher,
        ObjectProvider<OutboxPublishedCallback> callbacks,
        Clock clock,
        PlatformTransactionManager transactionManager
    ) {
        return new OutboxRelay(outboxStore, publisher, callbacks.orderedStream().toList(), clock, transactionManager);
    }
}
