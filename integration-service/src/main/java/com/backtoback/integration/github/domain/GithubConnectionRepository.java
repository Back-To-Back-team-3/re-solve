package com.backtoback.integration.github.domain;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * GitHub 연동 저장소 (erd.md > 4.8.1).
 */
public interface GithubConnectionRepository extends JpaRepository<GithubConnection, Long> {

    Optional<GithubConnection> findByMemberId(Long memberId);

    boolean existsByMemberId(Long memberId);

    Optional<GithubConnection> findByInstallationId(Long installationId);
}
