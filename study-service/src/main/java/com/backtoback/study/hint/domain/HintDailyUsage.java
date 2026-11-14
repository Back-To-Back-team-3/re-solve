package com.backtoback.study.hint.domain;

import java.time.LocalDate;

import com.backtoback.common.jpa.entity.BaseTimeEntity;

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
 * 일일 힌트 사용량(ERD 4.6.2 hint_daily_usages).
 * 조건부 UPDATE·증감 메서드는 힌트 요청 기능 Issue에서 추가한다.
 */
@Getter
@Entity
@Table(
    name = "hint_daily_usages",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_hint_daily_usages_member_usage_date",
            columnNames = {
                "member_id",
                "usage_date"
            }
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class HintDailyUsage extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "member_id",
        nullable = false
    )
    private Long memberId;

    @Column(
        name = "usage_date",
        nullable = false
    )
    private LocalDate usageDate;

    @Column(
        name = "used_count",
        nullable = false
    )
    private int usedCount;
}
