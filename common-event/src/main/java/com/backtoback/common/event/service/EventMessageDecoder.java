package com.backtoback.common.event.service;

import java.time.OffsetDateTime;
import java.util.UUID;

import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.dto.ReceivedEvent;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Raw 이벤트 또는 SNS Notification 봉투를 해제하고 공통 메타데이터를 검사한다.
 * 업무 필드의 값·순서 판단과 큐·토픽의 신뢰 경계는 서비스가 관리한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>이벤트 계약서 v1.0 / §1.1 공통 헤더·§1.2 표기 규칙·§2.1 SNS 토픽: Raw 본문과 공통 형식</li>
 * <li>공통 모듈 적용 가이드 / §2.4 common-event·common-scheduling — 이벤트와 스케줄링 설계: SNS 봉투 해제</li>
 * </ul>
 */
@RequiredArgsConstructor
public class EventMessageDecoder {
    private final ObjectMapper objectMapper;

    /**
     * 수신한 JSON에서 공통 헤더를 분리하되 선택적 업무 필드는 그대로 보존한다.
     * SNS 구독 확인 메시지는 업무 이벤트로 실행하지 않는다.
     *
     * @param message SQS 본문 또는 그 안에 담긴 SNS Notification JSON
     * @return 공통 형식 검사를 통과한 이벤트
     * @throws IllegalArgumentException 본문·SNS 봉투·메타데이터 형식이 계약과 다를 때
     * @throws tools.jackson.core.JacksonException JSON 문법을 해석할 수 없을 때
     */
    public ReceivedEvent decode(String message) {
        if (message == null || message.isBlank()) {
            throw new IllegalArgumentException("Event message must not be blank");
        }
        var body = requireObject(objectMapper.readTree(message));
        if (!body.has("eventId") && body.has("Type")) {
            if (!"Notification".equals(requireText(body, "Type"))) {
                throw new IllegalArgumentException("Only SNS Notification envelopes are supported");
            }
            body = requireObject(objectMapper.readTree(requireText(body, "Message")));
        }
        if (body.has("aggregate_type")) {
            throw new IllegalArgumentException("aggregate_type belongs only to the Outbox row");
        }
        var eventId = requireText(body, "eventId");
        if (!eventId.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")) {
            throw new IllegalArgumentException("eventId must use the complete UUID format");
        }
        var occurredAt = requireText(body, "occurredAt");
        if (!occurredAt.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}\\.[0-9]{6}\\+09:00")) {
            throw new IllegalArgumentException("occurredAt must use +09:00 and six fractional digits");
        }
        var metadata
            = new EventMetadata(
                UUID.fromString(eventId),
                requireText(body, "eventType"),
                requireText(body, "schemaVersion"),
                OffsetDateTime.parse(occurredAt),
                requireText(body, "aggregateId"),
                requireText(body, "correlationId")
            );
        var payload = body.deepCopy();
        for (var header : new String[] {
            "eventId",
            "eventType",
            "schemaVersion",
            "occurredAt",
            "aggregateId",
            "correlationId"
        }) {
            payload.remove(header);
        }
        return new ReceivedEvent(metadata, payload);
    }

    /**
     * 배열·null·문자열을 이벤트 객체로 오인하지 않도록 JSON 루트 타입을 검사한다.
     *
     * @param node 파싱한 JSON 루트
     * @return 검사한 JSON 객체
     * @throws IllegalArgumentException 루트가 객체가 아닐 때
     */
    private ObjectNode requireObject(JsonNode node) {
        if (!(node instanceof ObjectNode object)) {
            throw new IllegalArgumentException("Event message must be a JSON object");
        }
        return object;
    }

    /**
     * 숫자·null을 문자열 헤더로 강제 변환하지 않고 필수 문자열만 읽는다.
     *
     * @param body 검사할 JSON 객체
     * @param field 필수 헤더 또는 봉투 필드 이름
     * @return 공백이 아닌 문자열 필드 값
     * @throws IllegalArgumentException 필드가 없거나 문자열이 아니거나 비어 있을 때
     */
    private String requireText(ObjectNode body, String field) {
        var value = body.get(field);
        if (value == null || !value.isString() || value.asString().isBlank()) {
            throw new IllegalArgumentException("A non-blank string is required for " + field);
        }
        return value.asString();
    }
}
