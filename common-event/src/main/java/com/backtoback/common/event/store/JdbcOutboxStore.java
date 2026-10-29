package com.backtoback.common.event.store;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.dto.OutboxEvent;
import com.backtoback.common.time.ClockConfiguration;

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

    /**
     * 미발행 행을 ID 순서로 최대 100건 선점하고 다른 Relay가 잠근 행은 기다리지 않는다.
     * 예약 회차의 진행 위치를 메모리에 보관하지 않아 재기동 후에도 DB 상태로 재선정한다.
     * <p>기준 문서: 스케줄러 v1.0 / §0.2.1 OutboxRelay — 이벤트 발행</p>
     *
     * @return 현재 발행 트랜잭션이 잠근 미발행 이벤트
     * @throws org.springframework.transaction.IllegalTransactionStateException 발행 트랜잭션이 없을 때
     * @throws org.springframework.dao.DataAccessException 선점 조회가 실패할 때
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public List<OutboxEvent> lockPendingBatch() {
        return jdbcTemplate.query("""
            SELECT id, aggregate_type, event_id, event_type, schema_version,
                occurred_at, aggregate_id, correlation_id, payload, retry_count
            FROM outbox_events
            WHERE status = 'PENDING'
            ORDER BY id LIMIT 100 FOR UPDATE SKIP LOCKED
            """, (row, rowNumber) -> readEvent(row));
    }

    /**
     * 원격 발행이 성공한 선점 행만 완료 상태로 전이하고 발행 시각을 기록한다.
     *
     * @param id 현재 트랜잭션이 선점한 Outbox PK
     * @param publishedAt 원격 응답 후 조회한 서울 시간
     * @throws org.springframework.transaction.IllegalTransactionStateException 발행 트랜잭션이 없을 때
     * @throws IllegalStateException 선점 행이 더 이상 발행 대기 상태가 아닐 때
     * @throws org.springframework.dao.DataAccessException 상태 저장이 실패할 때
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markPublished(long id, LocalDateTime publishedAt) {
        var updated = jdbcTemplate.update("""
            UPDATE outbox_events SET status = 'PUBLISHED', published_at = ?, updated_at = ?
            WHERE id = ? AND status = 'PENDING'
            """, Timestamp.valueOf(publishedAt), Timestamp.valueOf(publishedAt), id);
        requireUpdated(updated);
    }

    /**
     * 실제 발행 실패 횟수를 한 번 증가시키고 6번째 실패부터 자동 발행 대상에서 제외한다.
     * 성공 후 업무 콜백 실패는 이 경로로 처리하지 않고 발행 트랜잭션 자체를 롤백한다.
     *
     * @param event 현재 트랜잭션이 선점한 이벤트와 기존 실패 횟수
     * @param failedAt 실패 시점의 서울 시간
     * @return 재시도 상한을 초과해 {@code FAILED}로 전이했으면 {@code true}
     * @throws org.springframework.transaction.IllegalTransactionStateException 발행 트랜잭션이 없을 때
     * @throws IllegalStateException 선점 행의 상태 또는 실패 횟수가 달라졌을 때
     * @throws org.springframework.dao.DataAccessException 실패 상태 저장이 실패할 때
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean recordFailure(OutboxEvent event, LocalDateTime failedAt) {
        var nextRetryCount = event.retryCount() + 1;
        var failed = nextRetryCount > 5;
        var updated
            = jdbcTemplate
                .update(
                    """
                        UPDATE outbox_events SET status = ?, retry_count = ?, updated_at = ?
                        WHERE id = ? AND status = 'PENDING' AND retry_count = ?
                        """,
                    failed ? "FAILED" : "PENDING",
                    nextRetryCount,
                    Timestamp.valueOf(failedAt),
                    event.id(),
                    event.retryCount()
                );
        requireUpdated(updated);
        return failed;
    }

    /**
     * 저장된 로컬 시각에 서비스 시간대를 붙여 최초 이벤트의 메타데이터를 복원한다.
     *
     * @param row 선점 SELECT 결과의 현재 행
     * @return 재발행에서도 같은 식별자와 메시지를 사용하는 이벤트
     * @throws SQLException JDBC 컬럼을 읽을 수 없을 때
     */
    private OutboxEvent readEvent(ResultSet row) throws SQLException {
        var metadata
            = new EventMetadata(
                UUID.fromString(row.getString("event_id")),
                row.getString("event_type"),
                row.getString("schema_version"),
                row
                    .getObject("occurred_at", LocalDateTime.class)
                    .atZone(ClockConfiguration.SERVICE_ZONE)
                    .toOffsetDateTime(),
                row.getString("aggregate_id"),
                row.getString("correlation_id")
            );
        return new OutboxEvent(
            row.getLong("id"),
            row.getString("aggregate_type"),
            metadata,
            row.getString("payload"),
            row.getInt("retry_count")
        );
    }

    /**
     * 선점한 상태와 다른 행을 성공 처리하지 않고 현재 발행 트랜잭션을 실패시킨다.
     *
     * @param updated 조건부 UPDATE로 변경한 행 수
     * @throws IllegalStateException 정확히 한 행을 변경하지 못했을 때
     */
    private void requireUpdated(int updated) {
        if (updated != 1) {
            throw new IllegalStateException("The claimed Outbox event could not be updated");
        }
    }
}
