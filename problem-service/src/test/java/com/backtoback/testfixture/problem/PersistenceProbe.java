package com.backtoback.testfixture.problem;

import com.backtoback.problem.global.entity.BaseEntity;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 서비스 기반 Entity의 감사 시각과 버전을 확인한다.
 * 기존 애플리케이션 테스트에서 스캔되지 않도록 서비스 기본 패키지 밖에 둔다.
 */
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "persistence_probe")
public class PersistenceProbe extends BaseEntity {
    @Id
    private Long id;

    private String name;

    private PersistenceProbe(Long id, String name) {
        this.id = id;
        this.name = name;
    }

    public static PersistenceProbe create(Long id, String name) {
        return new PersistenceProbe(id, name);
    }

    public void rename(String name) {
        this.name = name;
    }
}
