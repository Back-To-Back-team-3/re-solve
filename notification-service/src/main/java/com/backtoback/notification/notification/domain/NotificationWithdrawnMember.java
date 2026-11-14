package com.backtoback.notification.notification.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원 탈퇴({@code MemberWithdrawn}) 이벤트 수신 이후 지연 도착하는 알림 생성·시험 예약 등록 및
 * SSE 재구독을 차단하기 위한 탈퇴 회원 불변 기록 엔티티 (ERD 4.7.4 notification_withdrawn_members).
 */
@Entity
@Table(
    name = "notification_withdrawn_members",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_notification_withdrawn_members_member_id",
            columnNames = {
                "member_id"
            }
        )
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class NotificationWithdrawnMember {

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

    @Column(
        name = "source_version",
        nullable = false
    )
    private int sourceVersion;

    @Column(
        name = "withdrawn_at",
        nullable = false
    )
    private LocalDateTime withdrawnAt;

    @Column(
        name = "created_at",
        nullable = false,
        updatable = false
    )
    private LocalDateTime createdAt;

    private NotificationWithdrawnMember(
        Long memberId,
        String sourceEventId,
        int sourceVersion,
        LocalDateTime withdrawnAt,
        LocalDateTime createdAt
    ) {
        this.memberId = memberId;
        this.sourceEventId = sourceEventId;
        this.sourceVersion = sourceVersion;
        this.withdrawnAt = withdrawnAt;
        this.createdAt = createdAt;
    }

    /**
     * 탈퇴 회원 기록 엔티티를 생성한다.
     */
    public static NotificationWithdrawnMember recordWithdrawal(
        Long memberId,
        String sourceEventId,
        int sourceVersion,
        LocalDateTime withdrawnAt,
        LocalDateTime createdAt
    ) {
        return new NotificationWithdrawnMember(memberId, sourceEventId, sourceVersion, withdrawnAt, createdAt);
    }
}
