package com.backtoback.problem.global.entity;

import java.time.LocalDateTime;
import java.time.ZoneId;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Version;
import lombok.Getter;

@Getter
@MappedSuperclass
public abstract class BaseEntity {
    @Column(
        nullable = false,
        updatable = false
    )
    private LocalDateTime createdAt;
    @Column(nullable = false)
    private LocalDateTime updatedAt;
    @Version
    @Column(nullable = false)
    private Long version;

    /**
     * 엔티티 최초 저장 시 생성 시각과 수정 시각을 서울 현재 시각으로 설정한다.
     */
    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
        updatedAt = createdAt;
    }

    /**
     * 엔티티 변경 시 수정 시각을 서울 현재 시각으로 갱신한다.
     */
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now(ZoneId.of("Asia/Seoul"));
    }
}
