package com.backtoback.problem.problem.service;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.backtoback.common.event.service.EventConsumer;
import com.backtoback.common.event.service.EventHandler;
import com.backtoback.common.event.service.EventMessageDecoder;

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;

/** DB 커밋 이후에만 수동 ACK로 Problem 공유 큐의 메시지를 삭제한다. */
public class ValidationResultListener {
    private final EventConsumer consumer;
    private final EventMessageDecoder decoder;
    private final Set<String> missingHandlers;

    /**
     * 이벤트 계약 §5의 Problem 구독 중 업무 핸들러가 준비되지 않은 종류를 구한다.
     *
     * @param consumer 커밋 후 ACK를 수행하는 소비기
     * @param decoder 공통 이벤트 해석기
     * @param handlers 현재 등록된 업무 핸들러
     */
    public ValidationResultListener(EventConsumer consumer, EventMessageDecoder decoder, List<EventHandler> handlers) {
        this.consumer = consumer;
        this.decoder = decoder;
        var missing
            = new HashSet<>(
                Set
                    .of(
                        "MemberUpdated",
                        "MemberSuspended",
                        "MemberWithdrawn",
                        "SubmissionJudged",
                        "ProblemValidationCompleted",
                        "ContestEnded",
                        "StudyMemberJoined",
                        "StudyMemberLeft"
                    )
            );
        handlers.forEach(handler -> missing.remove(handler.eventType()));
        this.missingHandlers = Set.copyOf(missing);
    }

    /**
     * 검증 메시지의 소비와 커밋 후 삭제를 공통 소비기에 위임한다.
     * 외부 메시지의 소비 트랜잭션은 공통 소비기가 독립적으로 관리한다.
     *
     * @param message SQS 원문 또는 SNS 봉투
     * @param acknowledgement 동기 수동 삭제 동작
     * @throws RuntimeException 처리·커밋·메시지 삭제가 실패한 경우
     */
    @SqsListener(
        value = "${PROBLEM_QUEUE_URL}",
        factory = "problemResultListenerFactory",
        acknowledgementMode = "MANUAL"
    )
    public void receive(String message, Acknowledgement acknowledgement) {
        String eventType = decoder.decode(message).metadata().eventType();
        if (missingHandlers.contains(eventType)) {
            throw new IllegalStateException("필수 구독 이벤트의 처리기가 준비되지 않았습니다: " + eventType);
        }
        consumer.consume(message, acknowledgement::acknowledge);
    }
}
