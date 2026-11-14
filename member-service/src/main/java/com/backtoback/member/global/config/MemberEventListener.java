package com.backtoback.member.global.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.stereotype.Component;

import com.backtoback.common.event.service.EventConsumer;

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import lombok.RequiredArgsConstructor;

/**
 * {@code member-queue}의 메시지를 공통 소비기에 넘긴다(공통 모듈 적용 가이드 2.4). 예외는 삼키지 않아 재전달·DLQ로 간다.
 */
@Component
@RequiredArgsConstructor
@ConditionalOnBooleanProperty("resolve.messaging.consumer-enabled")
public class MemberEventListener {

    private final EventConsumer eventConsumer;

    @SqsListener(
        value = "${resolve.messaging.queue-url}",
        factory = "memberSqsListenerFactory",
        acknowledgementMode = "MANUAL"
    )
    public void receive(String message, Acknowledgement acknowledgement) {
        eventConsumer.consume(message, acknowledgement::acknowledge);
    }
}
