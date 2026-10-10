package com.backtoback.contest.global.entity;

import java.time.LocalDateTime;

import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.MappedSuperclass;
import lombok.Getter;

/**
 * 생성 시간과 마지막 갱신 시간을 공통으로 관리한다.
 * JPA 감사 리스너가 저장·변경 시 시간을 설정하며 createdAt은 이후 UPDATE 대상에서 제외한다.
 * JdbcTemplate 등으로 실행한 직접 SQL에는 이 리스너가 적용되지 않는다.
 * <p>기준 문서:
 * <ul>
 * <li>도메인 및 데이터베이스 v1.0 / §4.4.1 시험 (exams), §4.4.2 시험 문제 (exam_problems),
 * §4.4.3 시험 참가자 (exam_participants): 공통 생성·갱신 시간 컬럼</li>
 * </ul>
 */
@Getter
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseTimeEntity {

    // 객체 생성 직후에는 null이며 JPA 감사 리스너가 저장 전에 설정한다. 최초 저장 후에는 수정하지 않는다.
    @CreatedDate
    @Column(
        nullable = false,
        updatable = false
    )
    private LocalDateTime createdAt;

    // 객체 생성 직후에는 null이며 JPA를 통한 저장·변경 시 갱신한다. 직접 SQL 실행에는 자동 반영되지 않는다.
    @LastModifiedDate
    @Column(nullable = false)
    private LocalDateTime updatedAt;
}
