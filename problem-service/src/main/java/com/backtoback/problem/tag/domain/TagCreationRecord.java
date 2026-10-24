package com.backtoback.problem.tag.domain;

import java.time.LocalDateTime;

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
    name = "tag_creation_records",
    uniqueConstraints = @UniqueConstraint(
        name = "uk_tag_creation_records_admin_key",
        columnNames = {
            "admin_id",
            "idempotency_key"
        }
    )
)
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class TagCreationRecord extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @Column(
        nullable = false,
        length = 128,
        updatable = false
    )
    private String adminId;
    @Column(
        nullable = false,
        length = 100,
        updatable = false
    )
    private String idempotencyKey;
    @Column(
        nullable = false,
        length = 200
    )
    private String requestName;
    @Column(nullable = false)
    private int requestDisplayOrder;
    @Column(nullable = false)
    private LocalDateTime expiresAt;
    // 태그가 삭제되어도 최초 응답을 반환해야 하므로 엔티티 연관관계를 맺지 않는다.
    private Long resultTagId;
    @Column(length = 200)
    private String resultName;
    private Integer resultDisplayOrder;

    /**
     * 기준 시각에 요청 기록의 유효 기간이 끝났는지 확인한다.
     *
     * @param now 만료 여부를 판단할 기준 시각
     * @return 만료 시각과 같거나 이후이면 true
     */
    public boolean isExpiredAt(LocalDateTime now) {
        return !expiresAt.isAfter(now);
    }

    /**
     * 요청한 태그 이름과 표시 순서가 보관된 최초 요청과 같은지 확인한다.
     * 이름의 대소문자와 앞뒤 공백을 포함해 원본 요청값을 비교한다.
     *
     * @param name 비교할 요청 이름
     * @param displayOrder 비교할 요청 표시 순서
     * @return 이름과 표시 순서가 모두 같으면 true
     */
    public boolean matches(String name, int displayOrder) {
        return requestName.equals(name) && requestDisplayOrder == displayOrder;
    }

    /**
     * 만료된 요청 기록을 새 요청 내용으로 갱신하고 이전 결과를 비운다.
     *
     * @param requestName 새 요청의 태그 이름
     * @param requestDisplayOrder 새 요청의 표시 순서
     * @param expiresAt 새 요청의 만료 시각
     */
    public void renew(String requestName, int requestDisplayOrder, LocalDateTime expiresAt) {
        this.requestName = requestName;
        this.requestDisplayOrder = requestDisplayOrder;
        this.expiresAt = expiresAt;
        resultTagId = null;
        resultName = null;
        resultDisplayOrder = null;
    }

    /**
     * 생성된 태그의 ID·이름·보정된 순번을 최초 응답으로 보관한다.
     * 태그의 이름·순번 변경이나 삭제 이후에도 같은 생성 응답을 반환하기 위한 기록이다.
     *
     * @param tagId 생성된 태그 ID
     * @param name 생성 당시 태그 이름
     * @param displayOrder 생성 당시 보정된 순번
     */
    public void complete(long tagId, String name, int displayOrder) {
        resultTagId = tagId;
        resultName = name;
        resultDisplayOrder = displayOrder;
    }
}
