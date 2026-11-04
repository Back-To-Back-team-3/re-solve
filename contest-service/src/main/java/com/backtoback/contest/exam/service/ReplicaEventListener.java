package com.backtoback.contest.exam.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.common.event.service.EventConsumer;
import com.backtoback.common.event.service.EventMessageDecoder;
import com.backtoback.common.event.store.JdbcProcessedEventStore;
import com.backtoback.contest.exam.client.MemberClient;

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import lombok.RequiredArgsConstructor;

/**
 * SQS 수신을 공통 소비기와 수동 ACK에 연결한다 (이벤트 계약서 1.4).
 */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(
    prefix = "contest.events",
    name = "enabled",
    havingValue = "true"
)
public class ReplicaEventListener {
    private final EventMessageDecoder decoder;
    private final EventConsumer consumer;
    private final MemberClient members;
    private final JdbcProcessedEventStore processedEvents;
    private final TransactionTemplate transactions;

    /**
     * 정지 이벤트의 부분 정보는 소비 트랜잭션 전에 회원 서비스에서 보완한다.
     */
    @SqsListener(
        value = "${contest.events.queue-url}",
        factory = "contestSqsListenerFactory",
        acknowledgementMode = "MANUAL"
    )
    public void receive(String message, Acknowledgement acknowledgement) {
        var event = decoder.decode(message);
        if ("MemberSuspended".equals(event.metadata().eventType()) && "v1".equals(event.metadata().schemaVersion())
            && !Boolean.TRUE
                .equals(transactions.execute(status -> processedEvents.exists(event.metadata().eventId())))) {
            long memberId = ReplicaEventHandlers.id(event.payload(), "memberId");
            var snapshot = members.fetch(memberId);
            ReplicaEventHandlers.withSnapshot(snapshot, () -> consumer.consume(message, acknowledgement::acknowledge));
        } else {
            consumer.consume(message, acknowledgement::acknowledge);
        }
    }
}
