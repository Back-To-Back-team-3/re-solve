package com.backtoback.common.event.store;

import java.sql.Timestamp;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.dto.EventMetadata;

import lombok.RequiredArgsConstructor;

/**
 * 연결된 서비스 스키마의 Outbox를 JDBC로 저장한다.
 * 테이블 생성과 Flyway 실행은 서비스가 담당하고 저장은 호출자의 업무 트랜잭션에 참여한다.
 * <p>기준 문서</p>
 * <ul>
 * <li>도메인 및 데이터베이스 v1.0 / §3.1 발행 대기 이벤트: 컬럼과 초기 상태</li>
 * <li>공통 모듈 적용 가이드 / §2.4 common-event·common-scheduling — 이벤트와 스케줄링 설계: DB 소유</li>
 * </ul>
 */
@RequiredArgsConstructor
public class JdbcOutboxStore {
    private final JdbcTemplate jdbcTemplate;

    /**
     * 업무 트랜잭션에서 발급한 이벤트를 발행 대기 상태로 기록한다.
     * 직접 호출해도 기존 트랜잭션이 없으면 저장 전에 실패한다.
     *
     * @param aggregateType 메시지에 포함하지 않는 애그리게이트 타입
     * @param metadata 저장할 이벤트의 공통 헤더
     * @param message 직렬화가 끝난 전체 이벤트 메시지
     * @throws org.springframework.transaction.IllegalTransactionStateException 기존 업무 트랜잭션이 없을 때
     * @throws org.springframework.dao.DataAccessException Outbox INSERT가 DB에서 실패할 때
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void insert(String aggregateType, EventMetadata metadata, String message) {
        var occurredAt = Timestamp.valueOf(metadata.occurredAt().toLocalDateTime());
        jdbcTemplate
            .update(
                """
                    INSERT INTO outbox_events (
                        event_id, event_type, schema_version, aggregate_type, aggregate_id,
                        correlation_id, payload, status, retry_count, occurred_at, created_at, updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, 'PENDING', 0, ?, ?, ?)
                    """,
                metadata.eventId().toString(),
                metadata.eventType(),
                metadata.schemaVersion(),
                aggregateType,
                metadata.aggregateId(),
                metadata.correlationId(),
                message,
                occurredAt,
                occurredAt,
                occurredAt
            );
    }
}
