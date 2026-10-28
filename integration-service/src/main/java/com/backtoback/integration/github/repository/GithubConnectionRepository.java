package com.backtoback.integration.github.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

import com.backtoback.integration.github.domain.GithubConnection;

/**
 * GitHub 연동 저장소 (erd.md > 4.8.1, convention-build-manual.md > 6.1).
 */
public interface GithubConnectionRepository extends JpaRepository<GithubConnection, Long> {

    Optional<GithubConnection> findByMemberId(Long memberId);

    boolean existsByMemberId(Long memberId);

    Optional<GithubConnection> findByInstallationId(Long installationId);
}
