package com.backtoback.integration.github.event.consumer;

import java.time.Clock;
import java.time.LocalDateTime;

import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.integration.github.event.dto.SubmissionJudgedEvent;
import com.backtoback.integration.github.service.GithubSyncJobService;
import com.backtoback.integration.infra.event.repository.ProcessedEventRepository;

import io.awspring.cloud.sqs.annotation.SqsListener;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * AWS SQS {@code integration-queue}로부터 judge-service의 {@code SubmissionJudged} 이벤트를 수신하여
 * 멱등하게 기록하고 AC 판정 건에 대해 GitHub 자동 커밋 작업을 생성하는 컨슈머 (event-contract.md > 4.3 J1, policy-and-state.md > 9.3).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SubmissionJudgedConsumer {

    private static final String MDC_CORRELATION_ID = "correlationId";
    private static final String MDC_EVENT_ID = "eventId";
    private static final String MDC_EVENT_TYPE = "eventType";
    private static final String SUPPORTED_EVENT_TYPE = "SubmissionJudged";

    private final ObjectMapper objectMapper;
    private final ProcessedEventRepository processedEventRepository;
    private final GithubSyncJobService githubSyncJobService;
    private final Clock clock;

    /**
     * SQS 메시지를 수신하여 도메인 이벤트를 처리한다.
     * 동일 이벤트 수신 시 멱등성을 보장하며, AC 판정 건인 경우 GitHub Sync Job을 등록한다.
     *
     * @param messageBody SQS 메시지 본문 (JSON)
     */
    @SqsListener("${integration.sqs.queue-name:integration-queue}")
    @Transactional
    public void consume(String messageBody) {
        JsonNode payload = extractEventPayload(messageBody);
        String eventId = extractRequiredField(payload, "eventId");
        String eventType = extractRequiredField(payload, "eventType");
        String correlationId = extractOptionalField(payload, "correlationId");

        putMdc(MDC_EVENT_ID, eventId);
        putMdc(MDC_EVENT_TYPE, eventType);
        putMdc(MDC_CORRELATION_ID, correlationId);

        try {
            if (processedEventRepository.existsByEventId(eventId)) {
                log.info("이미 처리된 중복 이벤트 무시: eventId={}, eventType={}", eventId, eventType);
                return;
            }

            LocalDateTime now = LocalDateTime.now(clock);
            processedEventRepository.insertIfAbsent(eventId, eventType, now);

            if (!SUPPORTED_EVENT_TYPE.equals(eventType)) {
                log.debug("처리 대상이 아닌 도메인 이벤트 수신 기록 완료: eventId={}, eventType={}", eventId, eventType);
                return;
            }

            SubmissionJudgedEvent event = objectMapper.treeToValue(payload, SubmissionJudgedEvent.class);
            if (!event.isAccepted()) {
                log
                    .debug(
                        "AC 판정이 아니므로 GitHub Sync Job 생성을 생략합니다: submissionId={}, verdict={}",
                        event.submissionId(),
                        event.verdict()
                    );
                return;
            }

            Long memberId = Long.parseLong(event.userId());
            Long submissionId = Long.parseLong(event.submissionId());
            Long problemId = Long.parseLong(event.problemId());
            int difficulty = event.problemDifficulty() != null ? event.problemDifficulty() : 1;

            githubSyncJobService.registerSyncJob(memberId, submissionId, problemId, event.problemTitle(), difficulty);
        } catch (DataIntegrityViolationException exception) {
            log.info("유일 제약 위반으로 중복 이벤트 처리 흡수 완료: eventId={}, eventType={}", eventId, eventType);
        } catch (Exception exception) {
            log
                .error(
                    "SubmissionJudged 이벤트 처리 중 오류 발생: eventId={}, error={}",
                    eventId,
                    exception.getMessage(),
                    exception
                );
            throw new RuntimeException("이벤트 처리 실패", exception);
        } finally {
            MDC.remove(MDC_EVENT_ID);
            MDC.remove(MDC_EVENT_TYPE);
            MDC.remove(MDC_CORRELATION_ID);
        }
    }

    private JsonNode extractEventPayload(String messageBody) {
        if (messageBody == null || messageBody.isBlank()) {
            throw new IllegalArgumentException("SQS 메시지 본문이 비어 있습니다.");
        }
        JsonNode root;
        try {
            root = objectMapper.readTree(messageBody);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 파싱 실패: " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new IllegalArgumentException("SQS 메시지 본문이 JSON 객체 형식이 아닙니다.");
        }

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
                // 언래핑 실패 시 root 사용
            }
        }
        return root;
    }

    private String extractRequiredField(JsonNode payload, String fieldName) {
        JsonNode node = payload.get(fieldName);
        if (node == null || node.isNull()) {
            throw new IllegalArgumentException("이벤트 공통 헤더 필드가 누락되었습니다: " + fieldName);
        }
        String value = node.asString().trim();
        if (value.isEmpty()) {
            throw new IllegalArgumentException("이벤트 공통 헤더 필드가 비어 있습니다: " + fieldName);
        }
        return value;
    }

    private String extractOptionalField(JsonNode payload, String fieldName) {
        JsonNode node = payload.get(fieldName);
        if (node == null || node.isNull()) {
            return null;
        }
        String value = node.asString().trim();
        return value.isEmpty() ? null : value;
    }

    private void putMdc(String key, String value) {
        if (value != null) {
            MDC.put(key, value);
        }
    }
}
