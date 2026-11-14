package com.backtoback.judge.judging.service;

import java.util.List;
import java.util.Set;

import com.backtoback.common.event.service.EventConsumer;
import com.backtoback.common.event.service.EventHandler;
import com.backtoback.common.event.service.EventMessageDecoder;

import io.awspring.cloud.sqs.annotation.SqsListener;
import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import lombok.RequiredArgsConstructor;

/** 시험·대회·진단 채점 요청을 하나의 큐에서 종류와 버전별 업무 처리기로 연결한다. */
@RequiredArgsConstructor
public class SubmissionMessageListener {
    private static final Set<String> REQUEST_EVENTS
        = Set.of("ExamSubmissionRequested", "ContestSubmissionRequested", "DiagnosisSubmissionRequested");

    private final EventConsumer consumer;
    private final EventMessageDecoder decoder;
    private final List<EventHandler> handlers;

    /**
     * 이 큐에 허용된 요청을 처리하고 모르는 종류는 소비 기록만 남긴 뒤 ACK한다.
     *
     * @param message SQS 요청 원문
     * @param acknowledgement 수동 삭제 동작
     * @throws IllegalArgumentException 알려진 요청이 잘못 라우팅됐거나 등록된 버전이 없는 경우
     * @throws RuntimeException 처리·커밋·삭제에 실패한 경우
     */
    @SqsListener(
        value = "${judge.messaging.submission-queue-url}",
        factory = "judgeManualAckListenerFactory",
        acknowledgementMode = "MANUAL"
    )
    public void receive(String message, Acknowledgement acknowledgement) {
        var metadata = decoder.decode(message).metadata();
        String type = metadata.eventType();
        boolean known
            = "ProblemValidationRequested".equals(type)
                || handlers.stream().anyMatch(handler -> handler.eventType().equals(type));
        if (isSubmissionRequest(type) ? handlers
            .stream()
            .noneMatch(
                handler -> handler.eventType().equals(type) && handler.schemaVersion().equals(metadata.schemaVersion())
            ) : known) {
            throw new IllegalArgumentException("지원하지 않는 채점 요청 이벤트입니다.");
        }
        consumer.consume(message, acknowledgement::acknowledge);
    }

    /**
     * 처리기 배포 여부와 무관하게 공용 제출 큐에 속하는 요청을 확인한다.
     *
     * @param eventType 수신 이벤트 종류
     * @return 제출 큐로 보내야 하는 요청인지 여부
     */
    static boolean isSubmissionRequest(String eventType) {
        return REQUEST_EVENTS.contains(eventType);
    }
}
