package com.backtoback.study.replica.listener;

import org.slf4j.MDC;
import org.springframework.stereotype.Component;

import com.backtoback.study.hint.event.dto.ContestTerminatedPayload;
import com.backtoback.study.hint.event.dto.ExamTerminatedPayload;
import com.backtoback.study.hint.event.dto.ExamUpdatedPayload;
import com.backtoback.study.hint.event.dto.ParticipantCanceledPayload;
import com.backtoback.study.hint.event.dto.ParticipantRegisteredPayload;
import com.backtoback.study.hint.service.HintBlockReplicaConsumerService;
import com.backtoback.study.membership.service.MemberWithdrawnConsumerService;
import com.backtoback.study.replica.event.dto.MemberWithdrawnPayload;
import com.backtoback.study.replica.event.dto.SubmissionJudgedPayload;
import com.backtoback.study.replica.service.SubmissionJudgedConsumerService;

import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * SQS {@code study-queue}에서 SubmissionJudged 이벤트를 수신하여 소비 서비스에 위임한다
 * (event-contract.md §J1, §1.4 소비 규칙).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubmissionJudgedEventListener {

    private static final String MEMBER_WITHDRAWN_EVENT_TYPE = "MemberWithdrawn";
    private static final String SUPPORTED_EVENT_TYPE = "SubmissionJudged";
    private static final String EXAM_PARTICIPANT_REGISTERED_EVENT_TYPE = "ExamParticipantRegistered";
    private static final String CONTEST_PARTICIPANT_REGISTERED_EVENT_TYPE = "ContestParticipantRegistered";
    private static final String EXAM_PARTICIPANT_CANCELED_EVENT_TYPE = "ExamParticipantCanceled";
    private static final String CONTEST_PARTICIPANT_CANCELED_EVENT_TYPE = "ContestParticipantCanceled";
    private static final String EXAM_UPDATED_EVENT_TYPE = "ExamUpdated";
    private static final String EXAM_CLOSED_EVENT_TYPE = "ExamClosed";
    private static final String EXAM_CANCELED_EVENT_TYPE = "ExamCanceled";
    private static final String CONTEST_ENDED_EVENT_TYPE = "ContestEnded";
    private static final String CONTEST_CANCELED_EVENT_TYPE = "ContestCanceled";
    private static final String MDC_CORRELATION_ID = "correlationId";
    private static final String MDC_EVENT_ID = "eventId";
    private static final String MDC_EVENT_TYPE = "eventType";

    private final ObjectMapper objectMapper;
    private final MemberWithdrawnConsumerService memberWithdrawnConsumerService;
    private final SubmissionJudgedConsumerService consumerService;
    private final HintBlockReplicaConsumerService hintBlockReplicaConsumerService;

    @SqsListener("${study.sqs.queue-name:study-queue}")
    public void onMessage(String messageBody) {
        JsonNode payload = extractPayload(messageBody);

        String eventId = nodeString(payload, "eventId");
        String eventType = nodeString(payload, "eventType");
        if (eventId != null) {
            MDC.put(MDC_EVENT_ID, eventId);
        }
        if (eventType != null) {
            MDC.put(MDC_EVENT_TYPE, eventType);
        }
        String correlationId = nodeString(payload, "correlationId");
        if (correlationId != null) {
            MDC.put(MDC_CORRELATION_ID, correlationId);
        }

        try {
            if (MEMBER_WITHDRAWN_EVENT_TYPE.equals(eventType)) {
                MemberWithdrawnPayload event = objectMapper.treeToValue(payload, MemberWithdrawnPayload.class);
                memberWithdrawnConsumerService.consume(event);
                return;
            }

            if (EXAM_PARTICIPANT_REGISTERED_EVENT_TYPE.equals(eventType)
                || CONTEST_PARTICIPANT_REGISTERED_EVENT_TYPE.equals(eventType)) {
                ParticipantRegisteredPayload event
                    = objectMapper.treeToValue(payload, ParticipantRegisteredPayload.class);
                hintBlockReplicaConsumerService.consumeParticipantRegistered(event);
                return;
            }

            if (EXAM_PARTICIPANT_CANCELED_EVENT_TYPE.equals(eventType)
                || CONTEST_PARTICIPANT_CANCELED_EVENT_TYPE.equals(eventType)) {
                ParticipantCanceledPayload event = objectMapper.treeToValue(payload, ParticipantCanceledPayload.class);
                hintBlockReplicaConsumerService.consumeParticipantCanceled(event);
                return;
            }

            if (EXAM_UPDATED_EVENT_TYPE.equals(eventType)) {
                ExamUpdatedPayload event = objectMapper.treeToValue(payload, ExamUpdatedPayload.class);
                hintBlockReplicaConsumerService.consumeExamUpdated(event);
                return;
            }

            if (EXAM_CLOSED_EVENT_TYPE.equals(eventType) || EXAM_CANCELED_EVENT_TYPE.equals(eventType)) {
                ExamTerminatedPayload event = objectMapper.treeToValue(payload, ExamTerminatedPayload.class);
                hintBlockReplicaConsumerService.consumeExamTerminated(event);
                return;
            }

            if (CONTEST_ENDED_EVENT_TYPE.equals(eventType) || CONTEST_CANCELED_EVENT_TYPE.equals(eventType)) {
                ContestTerminatedPayload event = objectMapper.treeToValue(payload, ContestTerminatedPayload.class);
                hintBlockReplicaConsumerService.consumeContestTerminated(event);
                return;
            }

            if (!SUPPORTED_EVENT_TYPE.equals(eventType)) {
                // event-contract.md §1.4 §1: 모르는 eventType은 processed_events만 기록 후 성공
                consumerService
                    .consumeUnknown(
                        eventId,
                        eventType,
                        nodeString(payload, "occurredAt"),
                        nodeString(payload, "aggregateId"),
                        correlationId
                    );
                log.warn("처리 대상이 아닌 이벤트 무시 eventType={}", eventType);
                return;
            }

            SubmissionJudgedPayload event = objectMapper.treeToValue(payload, SubmissionJudgedPayload.class);
            consumerService.consume(event);
        } finally {
            MDC.remove(MDC_EVENT_ID);
            MDC.remove(MDC_EVENT_TYPE);
            MDC.remove(MDC_CORRELATION_ID);
        }
    }

    private String nodeString(JsonNode node, String field) {
        JsonNode fieldNode = node.get(field);
        return (fieldNode != null && !fieldNode.isNull()) ? fieldNode.asString() : null;
    }

    private JsonNode extractPayload(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            // SNS Envelope(RawMessageDelivery 비활성화 환경) 언래핑
            JsonNode typeNode = root.get("Type");
            JsonNode messageNode = root.get("Message");
            if (typeNode != null && "Notification".equals(typeNode.asString()) && messageNode != null
                && messageNode.isString()) {
                try {
                    JsonNode unwrapped = objectMapper.readTree(messageNode.asString());
                    if (unwrapped != null && unwrapped.isObject()) {
                        return unwrapped;
                    }
                } catch (Exception ignored) {
                    // 언래핑 실패 시 root 그대로 사용
                }
            }
            return root;
        } catch (Exception e) {
            throw new IllegalArgumentException("SQS 메시지 파싱 실패: " + e.getMessage(), e);
        }
    }
}
