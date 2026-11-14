package com.backtoback.problem.tag.domain;

import com.backtoback.problem.global.entity.BaseEntity;

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

/** 태그의 이름과 표시 순서를 관리한다. */
@Entity
@Table(
    name = "tags",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_tags_name",
            columnNames = "name"
        ),
        @UniqueConstraint(
            name = "uk_tags_display_order",
            columnNames = "display_order"
        )
    }
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Tag extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(
        nullable = false,
        length = 200
    )
    private String name;
    @Column(nullable = false)
    private int displayOrder;

    private Tag(String name, int displayOrder) {
        this.name = name;
        this.displayOrder = displayOrder;
    }

    public static Tag create(String name, int displayOrder) {
        return new Tag(name, displayOrder);
    }

    /**
     * {@code null}로 전달한 속성은 기존 값을 유지한다.
     */
    public void update(String name, Integer displayOrder) {
        if (name != null) {
            this.name = name;
        }
        if (displayOrder != null) {
            this.displayOrder = displayOrder;
        }
    }
}
