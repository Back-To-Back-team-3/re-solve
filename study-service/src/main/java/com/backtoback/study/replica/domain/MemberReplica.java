package com.backtoback.study.replica.domain;

import java.time.LocalDateTime;

import com.backtoback.common.jpa.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * 회원 정보 복제본(ERD 4.4.17 member_replicas). 닉네임·회원 상태 표시에 쓰며,
 * 반영 조건({@code sourceVersion} 비교)은 회원 정보 이벤트 소비 기능 Issue에서 구현한다.
 */
@Getter
@Entity
@Table(
    name = "member_replicas",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_member_replicas_member_id",
            columnNames = "member_id"
        )
    }
)
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MemberReplica extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "member_id",
        nullable = false
    )
    private Long memberId;

    @Column(
        nullable = false,
        length = 50
    )
    private String nickname;

    @Column(
        name = "profile_image_url",
        length = 500
    )
    private String profileImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(
        name = "member_status",
        nullable = false,
        length = 30
    )
    private MemberStatus memberStatus;

    @Column(
        name = "source_version",
        nullable = false
    )
    private Long sourceVersion;

    @Column(
        name = "synced_at",
        nullable = false
    )
    private LocalDateTime syncedAt;

    private MemberReplica(
        Long memberId,
        String nickname,
        String profileImageUrl,
        MemberStatus memberStatus,
        Long sourceVersion,
        LocalDateTime syncedAt
    ) {
        this.memberId = memberId;
        this.nickname = nickname;
        this.profileImageUrl = profileImageUrl;
        this.memberStatus = memberStatus;
        this.sourceVersion = sourceVersion;
        this.syncedAt = syncedAt;
    }

    /**
     * 회원 1명의 표시 정보 복제본을 만든다. 지연 이벤트 덮어쓰기 방지 비교는 이벤트 소비 기능에서 처리한다(ERD 4.4.17).
     */
    public static MemberReplica sync(
        Long memberId,
        String nickname,
        String profileImageUrl,
        MemberStatus memberStatus,
        Long sourceVersion,
        LocalDateTime syncedAt
    ) {
        return new MemberReplica(memberId, nickname, profileImageUrl, memberStatus, sourceVersion, syncedAt);
    }
}
