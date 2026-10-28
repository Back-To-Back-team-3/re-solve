package com.backtoback.integration.infra.event.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 수신 및 소비 완료된 이벤트 멱등 기록 엔티티 (erd.md > 3.2, scheduler.md > 0.2.3).
 * 동일 이벤트의 중복 처리를 방지하며, 14일 경과 시 배치 스케줄러를 통해 정리된다.
 */
@Entity
@Table(name = "processed_events")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class ProcessedEvent {

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
        name = "processed_at",
        nullable = false
    )
    private LocalDateTime processedAt;

    @Column(
        name = "created_at",
        nullable = false
    )
    private LocalDateTime createdAt;

    @Builder
    public ProcessedEvent(String eventId, String eventType, LocalDateTime processedAt, LocalDateTime createdAt) {
        if (eventId == null || eventId.isBlank()) {
            throw new IllegalArgumentException("eventId는 필수입니다.");
        }
        if (eventType == null || eventType.isBlank()) {
            throw new IllegalArgumentException("eventType은 필수입니다.");
        }
        this.eventId = eventId;
        this.eventType = eventType;
        this.processedAt = (processedAt != null) ? processedAt : LocalDateTime.now();
        this.createdAt = (createdAt != null) ? createdAt : LocalDateTime.now();
    }
}
