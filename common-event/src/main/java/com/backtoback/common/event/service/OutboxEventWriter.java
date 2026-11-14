package com.backtoback.common.event.service;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.store.JdbcOutboxStore;
import com.backtoback.common.time.ClockConfiguration;

import lombok.RequiredArgsConstructor;

/**
 * 업무 트랜잭션에서 이벤트 ID와 발생 시각을 발급하고 직렬화한 메시지를 Outbox에 저장한다.
 * 실제 SNS/SQS 발행은 커밋 후 Relay가 담당한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>이벤트 계약서 v1.0 / §1.1 공통 헤더·§1.3 발행 규칙: UUID v4와 업무 변경·Outbox 원자 저장</li>
 * <li>이벤트 계약서 v1.0 / §1.7 크기·보관: 직렬화한 메시지의 256KB 상한</li>
 * </ul>
 */
@RequiredArgsConstructor
public class OutboxEventWriter {
    private static final int MAX_MESSAGE_BYTES = 256 * 1024;

    private final JdbcOutboxStore outboxStore;
    private final EventJsonCodec eventJsonCodec;
    private final Clock clock;

    /**
     * 기존 업무 트랜잭션에 참여하며 트랜잭션이 없으면 저장 전에 실패한다.
     *
     * @param aggregateType DB에만 기록할 애그리게이트 타입
     * @param eventType 발행할 이벤트명
     * @param schemaVersion 메시지 형식 버전
     * @param aggregateId 숫자로 구성한 업무 대상 ID
     * @param correlationId 원 요청 또는 스케줄러의 추적 ID
     * @param payload 공통 헤더를 제외한 업무 필드 객체
     * @return 저장한 이벤트의 공통 메타데이터
     * @throws IllegalArgumentException 공통 메타데이터·업무 필드 구조가 계약과 다르거나 메시지가 256KB를 넘을 때
     * @throws org.springframework.transaction.IllegalTransactionStateException 기존 업무 트랜잭션이 없을 때
     * @throws tools.jackson.core.JacksonException 업무 객체를 JSON으로 변환할 수 없을 때
     * @throws org.springframework.dao.DataAccessException Outbox INSERT가 DB에서 실패할 때
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public EventMetadata append(
        String aggregateType,
        String eventType,
        String schemaVersion,
        String aggregateId,
        String correlationId,
        Object payload
    ) {
        if (aggregateType == null || aggregateType.isBlank() || aggregateType.length() > 30) {
            throw new IllegalArgumentException("aggregateType is blank or exceeds its column length");
        }
        var metadata
            = new EventMetadata(
                UUID.randomUUID(),
                eventType,
                schemaVersion,
                OffsetDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE),
                aggregateId,
                correlationId
            );
        var message = eventJsonCodec.serialize(metadata, payload);
        if (message.getBytes(StandardCharsets.UTF_8).length > MAX_MESSAGE_BYTES) {
            throw new IllegalArgumentException("Serialized event message exceeds 256KB in UTF-8");
        }
        outboxStore.insert(aggregateType, metadata, message);
        return metadata;
    }
}
