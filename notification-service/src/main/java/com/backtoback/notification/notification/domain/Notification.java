package com.backtoback.notification.notification.domain;

import java.time.LocalDateTime;

import com.backtoback.notification.global.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(
    name = "notifications",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_notifications_source_member_type",
            columnNames = {
                "source_event_id",
                "member_id",
                "type"
            }
        )
    },
    indexes = {
        @Index(
            name = "idx_notifications_member_is_read_created_at",
            columnList = "member_id, is_read, created_at"
        ),
        @Index(
            name = "idx_notifications_created_at",
            columnList = "created_at"
        )
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Notification extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "member_id",
        nullable = false
    )
    private Long memberId;

    @Column(
        name = "source_event_id",
        nullable = false,
        length = 64
    )
    private String sourceEventId;

    @Enumerated(EnumType.STRING)
    @Column(
        name = "type",
        nullable = false,
        length = 50
    )
    private NotificationType type;

    @Column(
        name = "title",
        nullable = false,
        length = 200
    )
    private String title;

    @Column(
        name = "content",
        nullable = false,
        length = 500
    )
    private String content;

    @Column(
        name = "link_url",
        length = 500
    )
    private String linkUrl;

    @Column(
        name = "is_read",
        nullable = false
    )
    private boolean isRead;

    @Column(name = "read_at")
    private LocalDateTime readAt;

    @Builder
    public Notification(
        Long memberId,
        String sourceEventId,
        NotificationType type,
        String title,
        String content,
        String linkUrl
    ) {
        this.memberId = memberId;
        this.sourceEventId = sourceEventId;
        this.type = type;
        this.title = title;
        this.content = content;
        this.linkUrl = linkUrl;
        this.isRead = false;
        this.readAt = null;
    }

    public void markAsRead(LocalDateTime readAt) {
        if (!this.isRead) {
            this.isRead = true;
            this.readAt = readAt;
        }
    }
}
