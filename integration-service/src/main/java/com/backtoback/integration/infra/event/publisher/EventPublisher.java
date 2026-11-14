package com.backtoback.integration.infra.event.publisher;

import com.backtoback.integration.infra.event.domain.OutboxEvent;

/**
 * 도메인 이벤트를 외부 메시지 브로커(SNS/SQS)로 발행하는 추상 인터페이스 (scheduler.md > 0.2.1).
 */
public interface EventPublisher {

    /**
     * Outbox 이벤트를 메시지 브로커로 발행한다.
     *
     * @param event 발행할 Outbox 이벤트
     * @throws Exception 발행 실패 시 발생
     */
    void publish(OutboxEvent event) throws Exception;
}
