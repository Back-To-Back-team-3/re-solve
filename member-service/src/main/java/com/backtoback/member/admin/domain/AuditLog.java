package com.backtoback.member.admin.domain;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관리자 작업 감사 로그 (ERD 3.3). 감사 대상 작업과 같은 트랜잭션에서 기록하는 불변 테이블이라 생성 시각만 둔다.
 */
@Getter
@Entity
@Table(name = "audit_logs")
@EntityListeners(AuditingEntityListener.class)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    public static final String TARGET_MEMBER = "MEMBER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        nullable = false,
        updatable = false
    )
    private Long actorId;

    @Column(
        nullable = false,
        length = 50,
        updatable = false
    )
    private String action;

    @Column(
        nullable = false,
        length = 30,
        updatable = false
    )
    private String targetType;

    @Column(
        nullable = false,
        length = 64,
        updatable = false
    )
    private String targetId;

    @Column(
        columnDefinition = "json",
        updatable = false
    )
    private String beforeState;

    @Column(
        columnDefinition = "json",
        updatable = false
    )
    private String afterState;

    @Column(
        nullable = false,
        length = 500,
        updatable = false
    )
    private String reason;

    @Column(
        length = 45,
        updatable = false
    )
    private String clientIp;

    @CreatedDate
    @Column(
        nullable = false,
        updatable = false,
        columnDefinition = "DATETIME(6)"
    )
    private LocalDateTime createdAt;

    private AuditLog(
        Long actorId,
        String action,
        String targetId,
        String beforeState,
        String afterState,
        String reason,
        String clientIp
    ) {
        this.actorId = actorId;
        this.action = action;
        this.targetType = TARGET_MEMBER;
        this.targetId = targetId;
        this.beforeState = beforeState;
        this.afterState = afterState;
        this.reason = reason;
        this.clientIp = clientIp;
    }

    /**
     * 회원 대상 관리자 작업 기록. 상태는 JSON 문자열로 받는다.
     */
    public static AuditLog ofMember(
        Long actorId,
        String action,
        Long memberId,
        String beforeState,
        String afterState,
        String reason,
        String clientIp
    ) {
        return new AuditLog(actorId, action, String.valueOf(memberId), beforeState, afterState, reason, clientIp);
    }
}
