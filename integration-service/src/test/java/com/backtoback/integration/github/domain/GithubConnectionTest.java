package com.backtoback.integration.github.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class GithubConnectionTest {

    @Test
    @DisplayName("연동 엔티티 생성 시 초기 상태는 CONNECTED이고 해제 사유는 null이며 기본적으로 autoCommitEnabled는 true이다")
    void createConnectionInitialState() {
        GithubConnection connection
            = GithubConnection
                .builder()
                .memberId(1L)
                .installationId(12345L)
                .encryptedToken(
                    new byte[] {
                        1,
                        2,
                        3
                    }
                )
                .githubLogin("test-user")
                .repositoryOwner("test-user")
                .repositoryName("algorithm-solutions")
                .commitPathRule("{난이도}/{문제번호}-{문제명}/풀이.java")
                .build();

        assertThat(connection.getStatus()).isEqualTo(GithubConnectionStatus.CONNECTED);
        assertThat(connection.getDisconnectedReason()).isNull();
        assertThat(connection.isAutoCommitEnabled()).isTrue();
        assertThat(connection.getMemberId()).isEqualTo(1L);
        assertThat(connection.getInstallationId()).isEqualTo(12345L);
        assertThat(connection.getEncryptedToken()).containsExactly(1, 2, 3);
    }

    @Test
    @DisplayName("연동 해제 시 토큰과 설치 ID가 파기되고 상태가 DISCONNECTED로 변경된다")
    void disconnectClearsSensitiveData() {
        GithubConnection connection
            = GithubConnection
                .builder()
                .memberId(1L)
                .installationId(12345L)
                .encryptedToken(
                    new byte[] {
                        1,
                        2,
                        3
                    }
                )
                .githubLogin("test-user")
                .repositoryOwner("test-user")
                .repositoryName("algorithm-solutions")
                .autoCommitEnabled(true)
                .commitPathRule("{난이도}/{문제번호}-{문제명}/풀이.java")
                .build();

        connection.disconnect(GithubDisconnectedReason.USER_REQUEST);

        assertThat(connection.getStatus()).isEqualTo(GithubConnectionStatus.DISCONNECTED);
        assertThat(connection.getDisconnectedReason()).isEqualTo(GithubDisconnectedReason.USER_REQUEST);
        assertThat(connection.getInstallationId()).isNull();
        assertThat(connection.getEncryptedToken()).isNull();
    }

    @Test
    @DisplayName("이미 DISCONNECTED 상태에서 다시 disconnect를 호출하면 IllegalStateException이 발생한다")
    void disconnectWhenAlreadyDisconnectedThrowsException() {
        GithubConnection connection
            = GithubConnection
                .builder()
                .memberId(1L)
                .installationId(12345L)
                .encryptedToken(
                    new byte[] {
                        1,
                        2,
                        3
                    }
                )
                .githubLogin("test-user")
                .repositoryOwner("test-user")
                .repositoryName("algorithm-solutions")
                .commitPathRule("{난이도}/{문제번호}-{문제명}/풀이.java")
                .build();

        connection.disconnect(GithubDisconnectedReason.USER_REQUEST);

        assertThatThrownBy(() -> connection.disconnect(GithubDisconnectedReason.WITHDRAWN))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("CONNECTED 상태에서만 연동을 해제할 수 있습니다");
    }

    @Test
    @DisplayName("CONNECTED 상태에서 reconnect를 호출하면 IllegalStateException이 발생한다")
    void reconnectWhenConnectedThrowsException() {
        GithubConnection connection
            = GithubConnection
                .builder()
                .memberId(1L)
                .installationId(12345L)
                .encryptedToken(
                    new byte[] {
                        1,
                        2,
                        3
                    }
                )
                .githubLogin("test-user")
                .repositoryOwner("test-user")
                .repositoryName("algorithm-solutions")
                .commitPathRule("{난이도}/{문제번호}-{문제명}/풀이.java")
                .build();

        assertThatThrownBy(
            () -> connection
                .reconnect(
                    99999L,
                    new byte[] {
                        9,
                        8,
                        7
                    },
                    "new-user",
                    "new-user",
                    "new-repo",
                    "{태그}/{문제번호}/풀이.java"
                )
        ).isInstanceOf(IllegalStateException.class).hasMessageContaining("DISCONNECTED 상태에서만 재연동할 수 있습니다");
    }

    @Test
    @DisplayName("재연동 시 새로운 설치 ID와 토큰이 등록되고 상태가 CONNECTED로 복원된다")
    void reconnectRestoresConnection() {
        GithubConnection connection
            = GithubConnection
                .builder()
                .memberId(1L)
                .installationId(12345L)
                .encryptedToken(
                    new byte[] {
                        1,
                        2,
                        3
                    }
                )
                .githubLogin("test-user")
                .repositoryOwner("test-user")
                .repositoryName("algorithm-solutions")
                .autoCommitEnabled(true)
                .commitPathRule("{난이도}/{문제번호}-{문제명}/풀이.java")
                .build();

        connection.disconnect(GithubDisconnectedReason.AUTH_EXPIRED);
        assertThat(connection.getStatus()).isEqualTo(GithubConnectionStatus.DISCONNECTED);

        connection
            .reconnect(
                99999L,
                new byte[] {
                    9,
                    8,
                    7
                },
                "new-user",
                "new-user",
                "new-repo",
                "{태그}/{문제번호}/풀이.java"
            );

        assertThat(connection.getStatus()).isEqualTo(GithubConnectionStatus.CONNECTED);
        assertThat(connection.getDisconnectedReason()).isNull();
        assertThat(connection.getInstallationId()).isEqualTo(99999L);
        assertThat(connection.getEncryptedToken()).containsExactly(9, 8, 7);
        assertThat(connection.getRepositoryName()).isEqualTo("new-repo");
    }

    @Test
    @DisplayName("설정 변경 메서드로 자동 커밋 여부와 경로 규칙을 수정할 수 있다")
    void updateSettings() {
        GithubConnection connection
            = GithubConnection
                .builder()
                .memberId(1L)
                .installationId(12345L)
                .encryptedToken(
                    new byte[] {
                        1,
                        2,
                        3
                    }
                )
                .githubLogin("test-user")
                .repositoryOwner("test-user")
                .repositoryName("algorithm-solutions")
                .autoCommitEnabled(true)
                .commitPathRule("{난이도}/{문제번호}-{문제명}/풀이.java")
                .build();

        connection.updateSettings(false, "custom-path/{문제번호}.java");

        assertThat(connection.isAutoCommitEnabled()).isFalse();
        assertThat(connection.getCommitPathRule()).isEqualTo("custom-path/{문제번호}.java");

        // null이나 빈 문자열 전달 시 기존 값 유지
        connection.updateSettings(null, "");
        assertThat(connection.isAutoCommitEnabled()).isFalse();
        assertThat(connection.getCommitPathRule()).isEqualTo("custom-path/{문제번호}.java");
    }

    @Test
    @DisplayName("생성 시 필수 필드가 null이거나 비어 있으면 IllegalArgumentException이 발생한다")
    void createWithNullOrBlankRequiredFieldsThrowsException() {
        // memberId null
        assertThatThrownBy(
            () -> GithubConnection
                .builder()
                .memberId(null)
                .githubLogin("test-user")
                .repositoryOwner("test-user")
                .repositoryName("repo")
                .commitPathRule("rule")
                .build()
        ).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("memberId는 필수");

        // githubLogin blank
        assertThatThrownBy(
            () -> GithubConnection
                .builder()
                .memberId(1L)
                .githubLogin(" ")
                .repositoryOwner("test-user")
                .repositoryName("repo")
                .commitPathRule("rule")
                .build()
        ).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("githubLogin은 필수");

        // repositoryOwner null
        assertThatThrownBy(
            () -> GithubConnection
                .builder()
                .memberId(1L)
                .githubLogin("test-user")
                .repositoryOwner(null)
                .repositoryName("repo")
                .commitPathRule("rule")
                .build()
        ).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("repositoryOwner는 필수");

        // repositoryName blank
        assertThatThrownBy(
            () -> GithubConnection
                .builder()
                .memberId(1L)
                .githubLogin("test-user")
                .repositoryOwner("test-user")
                .repositoryName("")
                .commitPathRule("rule")
                .build()
        ).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("repositoryName은 필수");

        // commitPathRule null
        assertThatThrownBy(
            () -> GithubConnection
                .builder()
                .memberId(1L)
                .githubLogin("test-user")
                .repositoryOwner("test-user")
                .repositoryName("repo")
                .commitPathRule(null)
                .build()
        ).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("commitPathRule은 필수");
    }

    @Test
    @DisplayName("재연동 시 필수 필드가 null이거나 비어 있으면 상태 변경 없이 IllegalArgumentException이 발생한다")
    void reconnectWithNullOrBlankRequiredFieldsThrowsException() {
        GithubConnection connection
            = GithubConnection
                .builder()
                .memberId(1L)
                .installationId(12345L)
                .encryptedToken(
                    new byte[] {
                        1,
                        2,
                        3
                    }
                )
                .githubLogin("test-user")
                .repositoryOwner("test-user")
                .repositoryName("algorithm-solutions")
                .commitPathRule("{난이도}/{문제번호}-{문제명}/풀이.java")
                .build();

        connection.disconnect(GithubDisconnectedReason.AUTH_EXPIRED);
        assertThat(connection.getStatus()).isEqualTo(GithubConnectionStatus.DISCONNECTED);

        assertThatThrownBy(
            () -> connection
                .reconnect(
                    99999L,
                    new byte[] {
                        9,
                        8,
                        7
                    },
                    "", // githubLogin blank
                    "new-user",
                    "new-repo",
                    "rule"
                )
        ).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("githubLogin은 필수");

        // 상태가 변경되지 않고 DISCONNECTED 유지 확인
        assertThat(connection.getStatus()).isEqualTo(GithubConnectionStatus.DISCONNECTED);
        assertThat(connection.getDisconnectedReason()).isEqualTo(GithubDisconnectedReason.AUTH_EXPIRED);
    }
}
