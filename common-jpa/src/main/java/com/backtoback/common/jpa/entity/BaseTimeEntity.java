package com.backtoback.common.jpa.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

/**
 * JPA 감사 리스너를 통해 생성 시간과 마지막 수정 시간을 관리한다.
 * 사용하는 서비스에서 공통 JPA 감사 설정을 등록해야 한다.
 * 직접 SQL·벌크 UPDATE에는 감사 리스너가 적용되지 않으므로 해당 쿼리에서 시간을 갱신해야 한다.
 * 생성 시간만 기록하는 불변 테이블에는 이 클래스를 적용하지 않는다.
 * <p>기준 문서</p>
 * <ul>
 * <li>도메인 및 데이터베이스 v1.0 / §5. 공통 컬럼 규칙: created_at·updated_at과 불변 테이블의 예외</li>
 * </ul>
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseTimeEntity {
    // 객체 생성 직후에는 null이며 최초 저장 시 설정하고 이후 UPDATE에서 제외한다.
    @CreatedDate
    @Column(
        name = "created_at",
        nullable = false,
        updatable = false,
        columnDefinition = "DATETIME(6)"
    )
    private LocalDateTime createdAt;

    // 객체 생성 직후에는 null이며 JPA를 통한 최초 저장과 변경 시 갱신한다.
    @LastModifiedDate
    @Column(
        name = "updated_at",
        nullable = false,
        columnDefinition = "DATETIME(6)"
    )
    private LocalDateTime updatedAt;
}
