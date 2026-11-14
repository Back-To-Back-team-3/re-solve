package com.backtoback.common.event.service;

import java.time.format.DateTimeFormatter;
import java.util.Set;

import com.backtoback.common.event.dto.EventMetadata;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * 서비스의 {@code ObjectMapper}로 공통 헤더와 업무 필드를 하나의 JSON 객체에 직렬화한다.
 * 업무 필드의 의미는 서비스가 검증하며 공통 헤더를 덮어쓰는 입력은 거절한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>이벤트 계약서 v1.0 / §1.1 공통 헤더·§1.2 표기 규칙: 평평한 JSON과 발생 시각 6자리 표기</li>
 * </ul>
 */
@RequiredArgsConstructor
public class EventJsonCodec {
    private static final DateTimeFormatter OCCURRED_AT_FORMAT
        = DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSSxxx");
    private static final Set<String> RESERVED_FIELDS
        = Set
            .of(
                "eventId",
                "eventType",
                "schemaVersion",
                "occurredAt",
                "aggregateId",
                "correlationId",
                "aggregate_type"
            );

    private final ObjectMapper objectMapper;

    /**
     * 입력 객체를 복사해 메타데이터를 합치므로 호출자가 전달한 JSON 객체를 변경하지 않는다.
     *
     * @param metadata 발행 시 유지할 공통 헤더
     * @param payload 서비스가 정의한 record 또는 JSON 객체
     * @return Outbox와 SNS/SQS가 그대로 사용할 전체 메시지 JSON
     * @throws IllegalArgumentException 업무 필드가 객체가 아니거나 공통 헤더와 충돌할 때
     * @throws tools.jackson.core.JacksonException 업무 객체를 JSON으로 변환할 수 없을 때
     */
    public String serialize(EventMetadata metadata, Object payload) {
        var tree = objectMapper.valueToTree(payload);
        if (!(tree instanceof ObjectNode body)) {
            throw new IllegalArgumentException("Event payload must be a JSON object");
        }
        for (var reservedField : RESERVED_FIELDS) {
            if (body.has(reservedField)) {
                throw new IllegalArgumentException("Event payload contains a reserved field: " + reservedField);
            }
        }
        var message = body.deepCopy();
        message.put("eventId", metadata.eventId().toString());
        message.put("eventType", metadata.eventType());
        message.put("schemaVersion", metadata.schemaVersion());
        message.put("occurredAt", OCCURRED_AT_FORMAT.format(metadata.occurredAt()));
        message.put("aggregateId", metadata.aggregateId());
        message.put("correlationId", metadata.correlationId());
        return objectMapper.writeValueAsString(message);
    }
}
