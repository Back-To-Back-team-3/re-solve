package com.backtoback.integration.github.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.backtoback.integration.github.client.GithubAppClient;
import com.backtoback.integration.github.client.dto.GithubInstallationResponse;
import com.backtoback.integration.github.client.dto.GithubInstallationTokenResponse;
import com.backtoback.integration.github.client.dto.GithubRepositoriesResponse;
import com.backtoback.integration.github.client.dto.GithubRepositoryDto;
import com.backtoback.integration.github.domain.GithubConnection;
import com.backtoback.integration.github.domain.GithubConnectionStatus;
import com.backtoback.integration.github.domain.GithubDisconnectedReason;
import com.backtoback.integration.github.dto.response.GithubConnectionInfoResponse;
import com.backtoback.integration.github.dto.response.GithubInstallResultResponse;
import com.backtoback.integration.github.dto.response.GithubSettingsResultResponse;
import com.backtoback.integration.github.repository.GithubConnectionRepository;
import com.backtoback.integration.global.error.IntegrationErrorCode;
import com.backtoback.integration.global.error.IntegrationException;

@ExtendWith(MockitoExtension.class)
class GithubConnectionServiceTest {

    @Mock
    private GithubConnectionRepository githubConnectionRepository;

    @Mock
    private GithubAppClient githubAppClient;

    @Mock
    private GithubStateStore githubStateStore;

    @Mock
    private TokenEncryptor tokenEncryptor;

    @Mock
    private TransactionTemplate transactionTemplate;

    @InjectMocks
    private GithubConnectionService githubConnectionService;

    @Test
    @DisplayName("연동 정보가 없으면 NOT_CONNECTED 상태 응답을 반환한다")
    void getConnectionWhenNotFound() {
        given(githubConnectionRepository.findByMemberId(1L)).willReturn(Optional.empty());

        GithubConnectionInfoResponse response = githubConnectionService.getConnection(1L);

        assertThat(response.status()).isEqualTo("NOT_CONNECTED");
        assertThat(response.githubLogin()).isNull();
    }

    @Test
    @DisplayName("연동 정보가 CONNECTED이면 저장소 및 설정 정보를 반환한다")
    void getConnectionWhenConnected() {
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
                .githubLogin("kim-dev")
                .repositoryOwner("kim-dev")
                .repositoryName("algo-repo")
                .commitPathRule("{난이도}/{문제번호}.java")
                .build();

        given(githubConnectionRepository.findByMemberId(1L)).willReturn(Optional.of(connection));

        GithubConnectionInfoResponse response = githubConnectionService.getConnection(1L);

        assertThat(response.status()).isEqualTo("CONNECTED");
        assertThat(response.githubLogin()).isEqualTo("kim-dev");
        assertThat(response.repositoryName()).isEqualTo("algo-repo");
        assertThat(response.isAutoCommitEnabled()).isTrue();
    }

    @Test
    @DisplayName("설치 URL 요청 시 state를 Redis에 보관하고 URL을 반환한다")
    void getInstallUrl() {
        given(githubAppClient.buildInstallUrl(anyString()))
            .willReturn("https://github.com/apps/test/installations/new?state=xyz");

        String url = githubConnectionService.getInstallUrl(1L);

        assertThat(url).contains("https://github.com/apps/test/installations/new");
        verify(githubStateStore).save(anyString(), eq(1L));
    }

    @Test
    @DisplayName("설치 완료 시 installationId 형식이 올바르지 않으면 state를 소비하지 않고 GITHUB_INSTALLATION_INVALID 예외가 발생한다")
    void installWithInvalidInstallationIdFormat() {
        assertThatThrownBy(() -> githubConnectionService.install(1L, "not-a-number", "valid-state"))
            .isInstanceOf(IntegrationException.class)
            .extracting(e -> ((IntegrationException)e).getErrorCode())
            .isEqualTo(IntegrationErrorCode.GITHUB_INSTALLATION_INVALID);

        verify(githubStateStore, never()).consume(anyString());
    }

    @Test
    @DisplayName("설치 완료 시 state가 유효하지 않으면 GITHUB_INSTALLATION_INVALID 예외가 발생한다")
    void installWithInvalidState() {
        given(githubStateStore.consume("invalid-state")).willReturn(Optional.empty());

        assertThatThrownBy(() -> githubConnectionService.install(1L, "12345", "invalid-state"))
            .isInstanceOf(IntegrationException.class)
            .extracting(e -> ((IntegrationException)e).getErrorCode())
            .isEqualTo(IntegrationErrorCode.GITHUB_INSTALLATION_INVALID);
    }

    @Test
    @DisplayName("설치 완료 시 타 회원이 이미 연결한 installationId이면 GITHUB_ALREADY_CONNECTED 예외가 발생한다")
    void installWithAlreadyConnectedByOtherMember() {
        given(githubStateStore.consume("valid-state")).willReturn(Optional.of(1L));

        GithubConnection otherConnection
            = GithubConnection
                .builder()
                .memberId(2L) // 다른 회원
                .installationId(12345L)
                .encryptedToken(
                    new byte[] {
                        1
                    }
                )
                .githubLogin("other")
                .repositoryOwner("other")
                .repositoryName("repo")
                .commitPathRule("rule")
                .build();

        given(githubConnectionRepository.findByInstallationId(12345L)).willReturn(Optional.of(otherConnection));

        assertThatThrownBy(() -> githubConnectionService.install(1L, "12345", "valid-state"))
            .isInstanceOf(IntegrationException.class)
            .extracting(e -> ((IntegrationException)e).getErrorCode())
            .isEqualTo(IntegrationErrorCode.GITHUB_ALREADY_CONNECTED);
    }

    @Test
    @DisplayName("선택된 저장소가 1개가 아니면 GITHUB_REPOSITORY_INVALID 예외가 발생한다")
    void installWithInvalidRepositoryCount() {
        given(githubStateStore.consume("valid-state")).willReturn(Optional.of(1L));
        given(githubConnectionRepository.findByInstallationId(12345L)).willReturn(Optional.empty());
        given(githubAppClient.getInstallation(12345L))
            .willReturn(
                new GithubInstallationResponse(12345L, new GithubInstallationResponse.Account(100L, "kim-dev", "User"))
            );
        given(githubAppClient.createInstallationAccessToken(12345L))
            .willReturn(new GithubInstallationTokenResponse("token", "2026-10-28T00:00:00Z"));
        // 저장소 2개 선택
        given(githubAppClient.getInstallationRepositories("token"))
            .willReturn(
                new GithubRepositoriesResponse(
                    2,
                    List
                        .of(
                            new GithubRepositoryDto(
                                1L,
                                "repo1",
                                "kim/repo1",
                                new GithubRepositoryDto.Owner(100L, "kim")
                            ),
                            new GithubRepositoryDto(
                                2L,
                                "repo2",
                                "kim/repo2",
                                new GithubRepositoryDto.Owner(100L, "kim")
                            )
                        )
                )
            );

        assertThatThrownBy(() -> githubConnectionService.install(1L, "12345", "valid-state"))
            .isInstanceOf(IntegrationException.class)
            .extracting(e -> ((IntegrationException)e).getErrorCode())
            .isEqualTo(IntegrationErrorCode.GITHUB_REPOSITORY_INVALID);
    }

    @Test
    @DisplayName("정상 설치 완료 시 토큰을 암호화하여 저장하고 연동 결과를 반환한다")
    void installSuccess() {
        given(githubStateStore.consume("valid-state")).willReturn(Optional.of(1L));
        given(githubConnectionRepository.findByInstallationId(12345L)).willReturn(Optional.empty());
        given(githubAppClient.getInstallation(12345L))
            .willReturn(
                new GithubInstallationResponse(12345L, new GithubInstallationResponse.Account(100L, "kim-dev", "User"))
            );
        given(githubAppClient.createInstallationAccessToken(12345L))
            .willReturn(new GithubInstallationTokenResponse("plain-token", "2026-10-28T00:00:00Z"));
        given(githubAppClient.getInstallationRepositories("plain-token"))
            .willReturn(
                new GithubRepositoriesResponse(
                    1,
                    List
                        .of(
                            new GithubRepositoryDto(
                                1L,
                                "algorithm-repo",
                                "kim-dev/algorithm-repo",
                                new GithubRepositoryDto.Owner(100L, "kim-dev")
                            )
                        )
                )
            );
        given(tokenEncryptor.encrypt("plain-token"))
            .willReturn(
                new byte[] {
                    9,
                    9,
                    9
                }
            );
        given(transactionTemplate.execute(any())).willAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(null);
        });
        given(githubConnectionRepository.findByMemberId(1L)).willReturn(Optional.empty());
        given(githubConnectionRepository.save(any(GithubConnection.class)))
            .willAnswer(invocation -> invocation.getArgument(0));

        GithubInstallResultResponse response = githubConnectionService.install(1L, "12345", "valid-state");

        assertThat(response.status()).isEqualTo("CONNECTED");
        assertThat(response.repositoryName()).isEqualTo("algorithm-repo");
        assertThat(response.isAutoCommitEnabled()).isTrue();
        // 암호화기 호출 검증
        verify(tokenEncryptor).encrypt("plain-token");
        verify(githubConnectionRepository).save(any(GithubConnection.class));
    }

    @Test
    @DisplayName("설치 정보 저장 시 DataIntegrityViolationException이 발생하면 GITHUB_ALREADY_CONNECTED 예외로 변환된다")
    void installWithDataIntegrityViolationThrowsAlreadyConnected() {
        given(githubStateStore.consume("valid-state")).willReturn(Optional.of(1L));
        given(githubConnectionRepository.findByInstallationId(12345L)).willReturn(Optional.empty());
        given(githubAppClient.getInstallation(12345L))
            .willReturn(
                new GithubInstallationResponse(12345L, new GithubInstallationResponse.Account(100L, "kim-dev", "User"))
            );
        given(githubAppClient.createInstallationAccessToken(12345L))
            .willReturn(new GithubInstallationTokenResponse("plain-token", "2026-10-28T00:00:00Z"));
        given(githubAppClient.getInstallationRepositories("plain-token"))
            .willReturn(
                new GithubRepositoriesResponse(
                    1,
                    List
                        .of(
                            new GithubRepositoryDto(
                                1L,
                                "algorithm-repo",
                                "kim-dev/algorithm-repo",
                                new GithubRepositoryDto.Owner(100L, "kim-dev")
                            )
                        )
                )
            );
        given(tokenEncryptor.encrypt("plain-token"))
            .willReturn(
                new byte[] {
                    1
                }
            );
        given(transactionTemplate.execute(any()))
            .willThrow(new DataIntegrityViolationException("Unique constraint violation"));

        assertThatThrownBy(() -> githubConnectionService.install(1L, "12345", "valid-state"))
            .isInstanceOf(IntegrationException.class)
            .extracting(e -> ((IntegrationException)e).getErrorCode())
            .isEqualTo(IntegrationErrorCode.GITHUB_ALREADY_CONNECTED);
    }

    @Test
    @DisplayName("연동 설정 변경 시 활성화 여부가 수정된다")
    void updateSettingsSuccess() {
        GithubConnection connection
            = GithubConnection
                .builder()
                .memberId(1L)
                .installationId(12345L)
                .encryptedToken(
                    new byte[] {
                        1
                    }
                )
                .githubLogin("kim-dev")
                .repositoryOwner("kim-dev")
                .repositoryName("repo")
                .commitPathRule("rule")
                .build();

        given(githubConnectionRepository.findByMemberId(1L)).willReturn(Optional.of(connection));

        GithubSettingsResultResponse response = githubConnectionService.updateSettings(1L, false);

        assertThat(response.isAutoCommitEnabled()).isFalse();
        assertThat(connection.isAutoCommitEnabled()).isFalse();
    }

    @Test
    @DisplayName("연동 정보가 없을 때 설정 변경을 시도하면 GITHUB_NOT_CONNECTED 예외가 발생한다")
    void updateSettingsWhenNotConnected() {
        given(githubConnectionRepository.findByMemberId(1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> githubConnectionService.updateSettings(1L, false))
            .isInstanceOf(IntegrationException.class)
            .extracting(e -> ((IntegrationException)e).getErrorCode())
            .isEqualTo(IntegrationErrorCode.GITHUB_NOT_CONNECTED);
    }

    @Test
    @DisplayName("연동 해제 시 엔티티의 disconnect가 호출된다")
    void disconnectSuccess() {
        GithubConnection connection
            = GithubConnection
                .builder()
                .memberId(1L)
                .installationId(12345L)
                .encryptedToken(
                    new byte[] {
                        1
                    }
                )
                .githubLogin("kim-dev")
                .repositoryOwner("kim-dev")
                .repositoryName("repo")
                .commitPathRule("rule")
                .build();

        given(githubConnectionRepository.findByMemberId(1L)).willReturn(Optional.of(connection));

        githubConnectionService.disconnect(1L);

        assertThat(connection.getStatus()).isEqualTo(GithubConnectionStatus.DISCONNECTED);
        assertThat(connection.getDisconnectedReason()).isEqualTo(GithubDisconnectedReason.USER_REQUEST);
    }
}
