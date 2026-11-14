package com.backtoback.common.event.dto;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import com.backtoback.common.time.ClockConfiguration;

/**
 * 서비스별 업무 필드와 함께 발행하는 공통 이벤트 메타데이터다.
 * 재발행 시 식별자와 발생 시각을 그대로 사용하며 업무 순서 값은 포함하지 않는다.
 * <p>기준 문서</p>
 * <ul>
 * <li>이벤트 계약서 v1.0 / §1.1 공통 헤더·§1.2 표기 규칙: UUID v4, 문자열 ID와 서울 시간 마이크로초</li>
 * <li>도메인 및 데이터베이스 v1.0 / §3.1 발행 대기 이벤트: 공통 컬럼 길이</li>
 * </ul>
 *
 * @param eventId 재발행에서도 유지할 UUID v4
 * @param eventType 발행할 이벤트명
 * @param schemaVersion 업무 순서와 구분되는 메시지 형식 버전
 * @param occurredAt 업무 트랜잭션의 이벤트 발생 시각
 * @param aggregateId 숫자로 구성한 애그리게이트 ID 문자열
 * @param correlationId 원 요청 또는 스케줄러의 추적 ID
 */
public record EventMetadata(
    UUID eventId,
    String eventType,
    String schemaVersion,
    OffsetDateTime occurredAt,
    String aggregateId,
    String correlationId
) {
    /**
     * 저장 가능한 헤더를 검증하고 발생 시각을 서울 시간과 마이크로초 정밀도로 통일한다.
     *
     * @throws IllegalArgumentException 필수값·형식·컬럼 길이가 계약과 다를 때
     */
    public EventMetadata {
        if (eventId == null || eventId.version() != 4 || eventId.variant() != 2) {
            throw new IllegalArgumentException("eventId must be a UUID v4");
        }
        requireText(eventType, 50, "eventType");
        requireText(schemaVersion, 10, "schemaVersion");
        requireText(aggregateId, 64, "aggregateId");
        requireText(correlationId, 64, "correlationId");
        if (!eventType.matches("[A-Z][A-Za-z0-9]*") || !schemaVersion.matches("v[1-9][0-9]*")
            || !aggregateId.matches("[0-9]+") || occurredAt == null) {
            throw new IllegalArgumentException("Event metadata does not match the event contract");
        }
        occurredAt
            = occurredAt
                .atZoneSameInstant(ClockConfiguration.SERVICE_ZONE)
                .toOffsetDateTime()
                .truncatedTo(ChronoUnit.MICROS);
    }

    /**
     * 공통 컬럼에 빈 값이나 길이를 초과한 값이 들어가지 않도록 검사한다.
     *
     * @param value 검사할 헤더 값
     * @param maxLength 해당 DB 컬럼의 최대 문자 수
     * @param field 오류에서 구분할 헤더 이름
     */
    private static void requireText(String value, int maxLength, String field) {
        if (value == null || value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(field + " is blank or exceeds its column length");
        }
    }
}
