package com.backtoback.integration.infra.event.domain;

import java.time.LocalDateTime;

import com.backtoback.common.jpa.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 트랜잭셔널 아웃박스 이벤트 엔티티 (erd.md > 3.1, scheduler.md > 0.2.1).
 * 도메인 상태 변경과 같은 트랜잭션에서 저장된 후 OutboxRelay를 통해 비동기로 발행된다.
 */
@Entity
@Table(name = "outbox_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OutboxEvent extends BaseTimeEntity {

    public static final int DEFAULT_MAX_RETRIES = 5;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "event_id",
        nullable = false,
        unique = true,
        length = 64
    )
    private String eventId;

    @Column(
        name = "event_type",
        nullable = false,
        length = 50
    )
    private String eventType;

    @Column(
        name = "schema_version",
        nullable = false,
        length = 10
    )
    private String schemaVersion;

    @Column(
        name = "aggregate_type",
        nullable = false,
        length = 30
    )
    private String aggregateType;

    @Column(
        name = "aggregate_id",
        nullable = false,
        length = 64
    )
    private String aggregateId;

    @Column(
        name = "correlation_id",
        nullable = false,
        length = 64
    )
    private String correlationId;

    @Column(
        name = "payload",
        nullable = false,
        columnDefinition = "json"
    )
    private String payload;

    @Enumerated(EnumType.STRING)
    @Column(
        name = "status",
        nullable = false,
        length = 30
    )
    private OutboxEventStatus status;

    @Column(
        name = "retry_count",
        nullable = false
    )
    private int retryCount;

    @Column(
        name = "occurred_at",
        nullable = false
    )
    private LocalDateTime occurredAt;

    @Column(name = "published_at")
    private LocalDateTime publishedAt;

    @Builder
    public OutboxEvent(
        String eventId,
        String eventType,
        String schemaVersion,
        String aggregateType,
        String aggregateId,
        String correlationId,
        String payload,
        LocalDateTime occurredAt
    ) {
        validateRequired(
            eventId,
            eventType,
            schemaVersion,
            aggregateType,
            aggregateId,
            correlationId,
            payload,
            occurredAt
        );
        this.eventId = eventId;
        this.eventType = eventType;
        this.schemaVersion = schemaVersion;
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.correlationId = correlationId;
        this.payload = payload;
        this.status = OutboxEventStatus.PENDING;
        this.retryCount = 0;
        this.occurredAt = occurredAt;
        this.publishedAt = null;
    }

    /**
     * 이벤트 발행 완료 상태로 전이한다 (scheduler.md > 0.2.1).
     *
     * @param publishedAt 발행 완료 시각
     */
    public void markPublished(LocalDateTime publishedAt) {
        this.status = OutboxEventStatus.PUBLISHED;
        this.publishedAt = publishedAt;
    }

    /**
     * 발행 실패를 기록하고 재시도 횟수를 증가시킨다. 최대 재시도 횟수를 초과하면 FAILED 상태로 전이한다 (scheduler.md > 0.2.1).
     *
     * @param maxRetries 허용 최대 재시도 횟수
     */
    public void recordFailure(int maxRetries) {
        this.retryCount++;
        if (this.retryCount > maxRetries) {
            this.status = OutboxEventStatus.FAILED;
        }
    }

    private void validateRequired(
        String eventId,
        String eventType,
        String schemaVersion,
        String aggregateType,
        String aggregateId,
        String correlationId,
        String payload,
        LocalDateTime occurredAt
    ) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId는 필수입니다.");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType은 필수입니다.");
        }
        if (schemaVersion == null || schemaVersion.isBlank()) {
            throw new IllegalArgumentException("schemaVersion은 필수입니다.");
        }
        if (aggregateType == null || aggregateType.isBlank()) {
            throw new IllegalArgumentException("aggregateType은 필수입니다.");
        }
        if (aggregateId == null || aggregateId.isBlank()) {
            throw new IllegalArgumentException("aggregateId는 필수입니다.");
        }
        if (correlationId == null || correlationId.isBlank()) {
            throw new IllegalArgumentException("correlationId는 필수입니다.");
        }
        if (payload == null || payload.isBlank()) {
            throw new IllegalArgumentException("payload는 필수입니다.");
        }
        if (occurredAt == null) {
            throw new IllegalArgumentException("occurredAt은 필수입니다.");
        }
    }
}
