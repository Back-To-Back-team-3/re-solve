package com.backtoback.study.operation.domain;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 관리자 조치 감사 로그 (ERD §3.3, audit_logs 테이블).
 */
@Entity
@Table(name = "audit_logs")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long actorId;

    @Column(
        length = 50,
        nullable = false
    )
    private String action;

    @Column(
        length = 30,
        nullable = false
    )
    private String targetType;

    @Column(
        length = 64,
        nullable = false
    )
    private String targetId;

    @Column(columnDefinition = "JSON")
    private String beforeState;

    @Column(columnDefinition = "JSON")
    private String afterState;

    @Column(
        length = 500,
        nullable = false
    )
    private String reason;

    @Column(length = 45)
    private String clientIp;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private AuditLog(
        Long actorId,
        String action,
        String targetType,
        String targetId,
        String beforeState,
        String afterState,
        String reason,
        String clientIp,
        LocalDateTime createdAt
    ) {
        this.actorId = actorId;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.beforeState = beforeState;
        this.afterState = afterState;
        this.reason = reason;
        this.clientIp = clientIp;
        this.createdAt = createdAt;
    }

    /**
     * 관리자 강제 스터디 종료 감사 로그를 생성한다.
     *
     * @param actorId 조치 관리자 ID
     * @param studyId 대상 스터디 ID
     * @param beforeStateJson 종료 전 상태 JSON
     * @param reason 조치 사유
     * @param clientIp 요청자 IP (nullable)
     * @param now 조치 시각
     */
    public static AuditLog forceStudyClosed(
        Long actorId,
        Long studyId,
        String beforeStateJson,
        String reason,
        String clientIp,
        LocalDateTime now
    ) {
        return new AuditLog(
            actorId,
            "FORCE_STUDY_CLOSED",
            "STUDY",
            String.valueOf(studyId),
            beforeStateJson,
            "{\"status\":\"CLOSED\"}",
            reason,
            clientIp,
            now
        );
    }
}
