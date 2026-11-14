package com.backtoback.common.event.dto;

/**
 * Relay가 선점한 Outbox 행과 이미 저장된 전체 메시지다.
 * 재직렬화하거나 새 식별자를 발급하지 않아 재발행에서도 같은 공통 헤더를 유지한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>이벤트 계약서 v1.0 / §1.3 발행 규칙: 재발행 시 같은 {@code eventId} 유지</li>
 * <li>도메인 및 데이터베이스 v1.0 / §3.1 발행 대기 이벤트: 저장한 메시지와 재시도 횟수</li>
 * </ul>
 *
 * @param id 선점한 Outbox 행의 PK
 * @param aggregateType DB에만 기록한 업무 대상 타입
 * @param metadata 저장 당시의 공통 헤더
 * @param message 공통 헤더와 업무 필드를 합친 저장 메시지
 * @param retryCount 지금까지 커밋한 발행 실패 횟수
 */
public record OutboxEvent(long id, String aggregateType, EventMetadata metadata, String message, int retryCount) {
}
