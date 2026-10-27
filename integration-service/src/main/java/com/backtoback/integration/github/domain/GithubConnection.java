package com.backtoback.integration.github.domain;

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
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * GitHub 연동 엔티티 (erd.md > 4.8.1).
 * 회원당 1개의 GitHub 저장소 연동 정보와 암호화된 토큰을 보관한다.
 */
@Entity
@Table(name = "github_connections")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class GithubConnection extends BaseTimeEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(
        name = "member_id",
        nullable = false,
        unique = true
    )
    private Long memberId;

    @Column(
        name = "installation_id",
        unique = true
    )
    private Long installationId;

    @Column(
        name = "encrypted_token",
        length = 1024
    )
    private byte[] encryptedToken;

    @Column(
        name = "github_login",
        nullable = false,
        length = 50
    )
    private String githubLogin;

    @Column(
        name = "repository_owner",
        nullable = false,
        length = 50
    )
    private String repositoryOwner;

    @Column(
        name = "repository_name",
        nullable = false,
        length = 200
    )
    private String repositoryName;

    @Column(
        name = "is_auto_commit_enabled",
        nullable = false
    )
    private boolean autoCommitEnabled;

    @Column(
        name = "commit_path_rule",
        nullable = false,
        length = 255
    )
    private String commitPathRule;

    @Enumerated(EnumType.STRING)
    @Column(
        name = "status",
        nullable = false,
        length = 30
    )
    private GithubConnectionStatus status;

    @Enumerated(EnumType.STRING)
    @Column(
        name = "disconnected_reason",
        length = 30
    )
    private GithubDisconnectedReason disconnectedReason;

    @Builder
    public GithubConnection(
        Long memberId,
        Long installationId,
        byte[] encryptedToken,
        String githubLogin,
        String repositoryOwner,
        String repositoryName,
        boolean autoCommitEnabled,
        String commitPathRule
    ) {
        this.memberId = memberId;
        this.installationId = installationId;
        this.encryptedToken = encryptedToken;
        this.githubLogin = githubLogin;
        this.repositoryOwner = repositoryOwner;
        this.repositoryName = repositoryName;
        this.autoCommitEnabled = autoCommitEnabled;
        this.commitPathRule = commitPathRule;
        this.status = GithubConnectionStatus.CONNECTED;
        this.disconnectedReason = null;
    }

    /**
     * GitHub 연동을 해제하고 인증 토큰과 설치 ID를 파기한다 (erd.md > 2.7, 4.8.1).
     *
     * @param reason 연동 해제 사유
     */
    public void disconnect(GithubDisconnectedReason reason) {
        this.status = GithubConnectionStatus.DISCONNECTED;
        this.installationId = null;
        this.encryptedToken = null;
        this.disconnectedReason = reason;
    }

    /**
     * 연동 해제 상태에서 다시 GitHub 연동을 활성화한다.
     *
     * @param installationId 새 GitHub App 설치 ID
     * @param encryptedToken 새로 암호화된 토큰
     * @param githubLogin GitHub 계정명
     * @param repositoryOwner 저장소 소유자명
     * @param repositoryName 저장소명
     * @param commitPathRule 커밋 경로 규칙
     */
    public void reconnect(
        Long installationId,
        byte[] encryptedToken,
        String githubLogin,
        String repositoryOwner,
        String repositoryName,
        String commitPathRule
    ) {
        this.status = GithubConnectionStatus.CONNECTED;
        this.installationId = installationId;
        this.encryptedToken = encryptedToken;
        this.githubLogin = githubLogin;
        this.repositoryOwner = repositoryOwner;
        this.repositoryName = repositoryName;
        this.commitPathRule = commitPathRule;
        this.disconnectedReason = null;
    }

    /**
     * 자동 커밋 활성화 여부 및 커밋 경로 규칙을 변경한다.
     *
     * @param autoCommitEnabled 자동 커밋 여부 (null이면 변경 안 함)
     * @param commitPathRule 커밋 경로 규칙 (null이면 변경 안 함)
     */
    public void updateSettings(Boolean autoCommitEnabled, String commitPathRule) {
        if (autoCommitEnabled != null) {
            this.autoCommitEnabled = autoCommitEnabled;
        }
        if (commitPathRule != null && !commitPathRule.isBlank()) {
            this.commitPathRule = commitPathRule;
        }
    }
}
