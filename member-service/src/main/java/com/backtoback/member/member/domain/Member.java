package com.backtoback.member.member.domain;

import java.time.LocalDateTime;

import com.backtoback.member.global.entity.BaseTimeEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "members")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseTimeEntity {

    private static final long INITIAL_PROFILE_VERSION = 1L;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        nullable = false,
        unique = true
    )
    private Long githubId;

    @Column(
        nullable = false,
        length = 50
    )
    private String githubLogin;

    @Column(
        nullable = false,
        length = 50
    )
    private String nickname;

    @Column(length = 255)
    private String email;

    @Column(length = 500)
    private String profileImageUrl;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30
    )
    private MemberRole role;

    @Enumerated(EnumType.STRING)
    @Column(
        nullable = false,
        length = 30
    )
    private MemberStatus status;

    @Column(nullable = false)
    private Long profileVersion;

    private LocalDateTime suspendedAt;

    private LocalDateTime withdrawnAt;

    private Member(Long githubId, String githubLogin, String email, String profileImageUrl) {
        this.githubId = githubId;
        this.githubLogin = githubLogin;
        this.nickname = githubLogin;
        this.email = email;
        this.profileImageUrl = profileImageUrl;
        this.role = MemberRole.USER;
        this.status = MemberStatus.ACTIVE;
        this.profileVersion = INITIAL_PROFILE_VERSION;
    }

    /**
     * GitHub 계정으로 신규 회원을 만든다. 닉네임은 GitHub 로그인명으로 시작한다.
     */
    public static Member registerWithGitHub(Long githubId, String githubLogin, String email, String profileImageUrl) {
        return new Member(githubId, githubLogin, email, profileImageUrl);
    }

    public boolean isWithdrawn() {
        return status == MemberStatus.WITHDRAWN;
    }
}
