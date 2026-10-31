package com.backtoback.judge.judging.service;

import com.backtoback.common.event.service.EventConsumer;

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import lombok.RequiredArgsConstructor;

/** DB 커밋 이후에만 수동 ACK로 검증 요청 메시지를 삭제한다. */
@RequiredArgsConstructor
public class ValidationMessageListener {
    private final EventConsumer consumer;

    /**
     * 검증 메시지의 소비와 커밋 후 삭제를 공통 소비기에 위임한다.
     * 외부 메시지의 소비 트랜잭션은 공통 소비기가 독립적으로 관리한다.
     *
     * @param message SQS 원문 또는 SNS 봉투
     * @param acknowledgement 동기 수동 삭제 동작
     * @throws RuntimeException 처리·커밋·메시지 삭제가 실패한 경우
     */
    @SqsListener(
        value = "${judge.messaging.validation-queue-url}",
        factory = "judgeValidationListenerFactory",
        acknowledgementMode = "MANUAL"
    )
    public void receive(String message, Acknowledgement acknowledgement) {
        consumer.consume(message, acknowledgement::acknowledge);
    }
}
