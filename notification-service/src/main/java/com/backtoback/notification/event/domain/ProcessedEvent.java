package com.backtoback.notification.event.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 도메인 이벤트 중복 소비를 차단하기 위한 멱등성 기록 엔티티 (ERD 3.2 processed_events).
 * 수정 시각 컬럼이 없는 불변 테이블이므로 {@code BaseTimeEntity}를 상속하지 않는다.
 */
@Entity
@Table(
    name = "processed_events",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_processed_events_event_id",
            columnNames = {
                "event_id"
            }
        )
    },
    indexes = {
        @Index(
            name = "idx_processed_events_processed_at",
            columnList = "processed_at"
        )
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "event_id",
        nullable = false,
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
        name = "processed_at",
        nullable = false
    )
    private LocalDateTime processedAt;

    @Column(
        name = "created_at",
        nullable = false,
        updatable = false
    )
    private LocalDateTime createdAt;

    private ProcessedEvent(String eventId, String eventType, LocalDateTime timestamp) {
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = timestamp;
        this.createdAt = timestamp;
    }

    public static ProcessedEvent create(String eventId, String eventType, LocalDateTime timestamp) {
        return new ProcessedEvent(eventId, eventType, timestamp);
    }
}
