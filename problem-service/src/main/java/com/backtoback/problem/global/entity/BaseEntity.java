package com.backtoback.problem.global.entity;

import com.backtoback.common.jpa.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;
import lombok.Getter;

/** 생성·수정 시각을 공통 기반에서 상속하고 문제 서비스 Entity의 낙관적 락 버전을 관리한다. */
@Getter
@MappedSuperclass
public abstract class BaseEntity extends BaseTimeEntity {
    @Version
    @Column(nullable = false)
    private Long version;
}
