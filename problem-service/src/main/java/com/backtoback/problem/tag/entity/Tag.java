package com.backtoback.problem.tag.entity;

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

    /**
     * 지정한 이름과 표시 순서로 태그를 생성한다.
     *
     * @param name 태그 이름
     * @param displayOrder 태그 목록의 표시 순서
     */
    public Tag(String name, int displayOrder) {
        this.name = name;
        this.displayOrder = displayOrder;
    }

    /**
     * 전달된 값이 있는 태그 속성만 수정한다.
     *
     * @param name 변경할 이름; null이면 유지
     * @param displayOrder 변경할 표시 순서; null이면 유지
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
