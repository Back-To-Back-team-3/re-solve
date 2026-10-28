package com.backtoback.integration.github.service;

import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * GitHub App 연동 및 상태 관리 비즈니스 로직 (api-spec.md > 8.1.1 ~ 8.1.5, policy-and-state.md > 9.3).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GithubConnectionService {

    private static final String DEFAULT_COMMIT_PATH_RULE = "{난이도}/{문제번호}-{문제명}/풀이.java";

    private final GithubConnectionRepository githubConnectionRepository;
    private final GithubAppClient githubAppClient;
    private final GithubStateStore githubStateStore;
    private final TokenEncryptor tokenEncryptor;

    /**
     * 회원의 현재 GitHub 연동 상태를 조회한다 (api-spec.md > 8.1.1).
     *
     * @param memberId 회원 ID
     * @return 연동 상태 정보
     */
    public GithubConnectionInfoResponse getConnection(Long memberId) {
        return githubConnectionRepository
            .findByMemberId(memberId)
            .filter(conn -> conn.getStatus() == GithubConnectionStatus.CONNECTED)
            .map(
                conn -> GithubConnectionInfoResponse
                    .connected(
                        conn.getGithubLogin(),
                        conn.getRepositoryOwner(),
                        conn.getRepositoryName(),
                        conn.isAutoCommitEnabled(),
                        conn.getCreatedAt()
                    )
            )
            .orElseGet(GithubConnectionInfoResponse::notConnected);
    }

    /**
     * GitHub App 설치 URL과 CSRF 방지용 state를 생성하고 Redis에 보관한다 (api-spec.md > 8.1.2).
     *
     * @param memberId 회원 ID
     * @return 설치 URL
     */
    public String getInstallUrl(Long memberId) {
        String state = UUID.randomUUID().toString();
        githubStateStore.save(state, memberId);
        return githubAppClient.buildInstallUrl(state);
    }

    /**
     * GitHub App 설치 완료 콜백을 처리하여 토큰을 발급·암호화하고 연동 정보를 저장한다 (api-spec.md > 8.1.3).
     *
     * @param memberId 회원 ID
     * @param installationIdStr GitHub App 설치 ID 문자열
     * @param state CSRF 검증용 state
     * @return 연동 완료 결과
     */
    @Transactional
    public GithubInstallResultResponse install(Long memberId, String installationIdStr, String state) {
        // 1. state 검증 (1회용 소비)
        Optional<Long> stateMemberId = githubStateStore.consume(state);
        if (stateMemberId.isEmpty() || !stateMemberId.get().equals(memberId)) {
            throw new IntegrationException(IntegrationErrorCode.GITHUB_INSTALLATION_INVALID);
        }

        // 2. installationId 파싱
        Long installationId;
        try {
            installationId = Long.parseLong(installationIdStr.trim());
        } catch (NumberFormatException e) {
            throw new IntegrationException(IntegrationErrorCode.GITHUB_INSTALLATION_INVALID);
        }

        // 3. 타 회원 중복 연결 검증
        githubConnectionRepository
            .findByInstallationId(installationId)
            .filter(
                conn -> !conn.getMemberId().equals(memberId) && conn.getStatus() == GithubConnectionStatus.CONNECTED
            )
            .ifPresent(conn -> {
                throw new IntegrationException(IntegrationErrorCode.GITHUB_ALREADY_CONNECTED);
            });

        // 4. GitHub API를 통해 설치 정보 및 저장소 목록 확인
        GithubInstallationResponse installation = githubAppClient.getInstallation(installationId);
        GithubInstallationTokenResponse tokenResponse = githubAppClient.createInstallationAccessToken(installationId);
        GithubRepositoriesResponse reposResponse = githubAppClient.getInstallationRepositories(tokenResponse.token());

        // 5. 선택 저장소 개수 검증 (정확히 1개여야 함)
        if (reposResponse == null || reposResponse.totalCount() != 1 || reposResponse.repositories().size() != 1) {
            throw new IntegrationException(IntegrationErrorCode.GITHUB_REPOSITORY_INVALID);
        }

        GithubRepositoryDto repository = reposResponse.repositories().get(0);
        String repositoryName = repository.name();
        String repositoryOwner = repository.owner().login();
        String githubLogin = (installation.account() != null) ? installation.account().login() : repositoryOwner;

        // 6. 토큰 AES-GCM 암호화
        byte[] encryptedToken = tokenEncryptor.encrypt(tokenResponse.token());

        // 7. 엔티티 저장 또는 재연결
        GithubConnection connection = githubConnectionRepository.findByMemberId(memberId).map(existing -> {
            if (existing.getStatus() == GithubConnectionStatus.CONNECTED) {
                existing.disconnect(GithubDisconnectedReason.USER_REQUEST);
            }
            existing
                .reconnect(
                    installationId,
                    encryptedToken,
                    githubLogin,
                    repositoryOwner,
                    repositoryName,
                    DEFAULT_COMMIT_PATH_RULE
                );
            return existing;
        })
            .orElseGet(
                () -> githubConnectionRepository
                    .save(
                        GithubConnection
                            .builder()
                            .memberId(memberId)
                            .installationId(installationId)
                            .encryptedToken(encryptedToken)
                            .githubLogin(githubLogin)
                            .repositoryOwner(repositoryOwner)
                            .repositoryName(repositoryName)
                            .autoCommitEnabled(true)
                            .commitPathRule(DEFAULT_COMMIT_PATH_RULE)
                            .build()
                    )
            );

        return new GithubInstallResultResponse(
            connection.getStatus().name(),
            connection.getRepositoryName(),
            connection.isAutoCommitEnabled()
        );
    }

    /**
     * GitHub 연동 설정을 변경한다 (api-spec.md > 8.1.4).
     *
     * @param memberId 회원 ID
     * @param autoCommitEnabled 자동 커밋 활성화 여부
     * @return 변경된 설정 결과
     */
    @Transactional
    public GithubSettingsResultResponse updateSettings(Long memberId, Boolean autoCommitEnabled) {
        GithubConnection connection
            = githubConnectionRepository
                .findByMemberId(memberId)
                .filter(conn -> conn.getStatus() == GithubConnectionStatus.CONNECTED)
                .orElseThrow(() -> new IntegrationException(IntegrationErrorCode.GITHUB_NOT_CONNECTED));

        connection.updateSettings(autoCommitEnabled, null);
        return new GithubSettingsResultResponse(connection.isAutoCommitEnabled());
    }

    /**
     * GitHub 연동을 해제한다 (api-spec.md > 8.1.5).
     *
     * @param memberId 회원 ID
     */
    @Transactional
    public void disconnect(Long memberId) {
        GithubConnection connection
            = githubConnectionRepository
                .findByMemberId(memberId)
                .filter(conn -> conn.getStatus() == GithubConnectionStatus.CONNECTED)
                .orElseThrow(() -> new IntegrationException(IntegrationErrorCode.GITHUB_NOT_CONNECTED));

        connection.disconnect(GithubDisconnectedReason.USER_REQUEST);
    }
}
