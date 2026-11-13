package com.backtoback.contest.exam.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import com.backtoback.common.event.service.EventConsumer;

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
    private final EventConsumer consumer;

    /**
     * 소비 트랜잭션의 커밋이 끝난 뒤에만 수동 ACK를 전달한다.
     */
    @SqsListener(
        value = "${contest.events.queue-url}",
        factory = "contestSqsListenerFactory",
        acknowledgementMode = "MANUAL"
    )
    public void receive(String message, Acknowledgement acknowledgement) {
        consumer.consume(message, acknowledgement::acknowledge);
    }
}
