package com.backtoback.member.member.domain;

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
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Getter
@Entity
@Table(name = "members")
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Member extends BaseTimeEntity {

    private static final long INITIAL_PROFILE_VERSION = 1L;

    // 정책 및 상태 9.4: 복제본과 같은 표시 이름
    private static final String WITHDRAWN_NICKNAME = "탈퇴한 사용자";

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

    public boolean isSuspended() {
        return status == MemberStatus.SUSPENDED;
    }

    /**
     * 정지한다 (API 명세서 1.6.2). 상태 변경도 {@code MemberUpdated}·{@code MemberSuspended}와 같은 순서를 쓰므로
     * {@code profileVersion}을 올린다(이벤트 계약 M1).
     */
    public void suspend(LocalDateTime now) {
        this.status = MemberStatus.SUSPENDED;
        this.suspendedAt = now;
        this.profileVersion++;
    }

    /**
     * 탈퇴한다 (API 명세서 1.2.3). 표시 정보와 연락처를 익명화하고 {@code profileVersion}을 올린다(이벤트 계약 M1~M3 공통 순서).
     * {@code githubId}는 재가입 정책이 정해질 때까지 남겨, 같은 GitHub 계정의 로그인을 거절하는 데 쓴다.
     */
    public void withdraw(LocalDateTime now) {
        this.status = MemberStatus.WITHDRAWN;
        this.withdrawnAt = now;
        this.suspendedAt = null;
        this.nickname = WITHDRAWN_NICKNAME;
        this.githubLogin = "withdrawn-" + id;
        this.email = null;
        this.profileImageUrl = null;
        this.profileVersion++;
    }

    /**
     * 정지를 해제한다 (API 명세서 1.6.2).
     */
    public void unsuspend() {
        this.status = MemberStatus.ACTIVE;
        this.suspendedAt = null;
        this.profileVersion++;
    }

    /**
     * 권한을 바꾼다 (API 명세서 1.6.3). 권한은 복제본에 없으므로 {@code profileVersion}을 올리지 않는다.
     */
    public void changeRole(MemberRole role) {
        this.role = role;
    }

    /**
     * 닉네임을 바꾸고 표시 정보 변경 순번({@code profileVersion})을 올린다. 같은 닉네임이면 아무것도 바꾸지 않는다.
     *
     * @return 닉네임이 바뀌었으면 {@code true}
     */
    public boolean changeNickname(String nickname) {
        if (this.nickname.equals(nickname)) {
            return false;
        }
        this.nickname = nickname;
        this.profileVersion++;
        return true;
    }
}
