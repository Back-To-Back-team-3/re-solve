package com.backtoback.integration.github.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.integration.github.domain.GithubConnection;
import com.backtoback.integration.github.domain.GithubSyncJob;
import com.backtoback.integration.github.domain.GithubSyncJobStatus;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class GithubSyncJobRepositoryTest {

    private static final String TEST_SECRET_KEY_BASE64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("integration_db");

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("integration.crypto.secret-key", () -> TEST_SECRET_KEY_BASE64);
    }

    @Autowired
    private GithubConnectionRepository githubConnectionRepository;

    @Autowired
    private GithubSyncJobRepository githubSyncJobRepository;

    private GithubConnection connection;

    @BeforeEach
    void setUp() {
        GithubConnection conn
            = GithubConnection
                .builder()
                .memberId(10L)
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
        connection = githubConnectionRepository.save(conn);
    }

    @Test
    @DisplayName("신규 작업을 저장하고 submissionId로 단일 조회할 수 있다")
    void saveAndFindBySubmissionId() {
        GithubSyncJob job = GithubSyncJob.createPending(connection, 1001L, 201L, "두 수의 합", 1);
        GithubSyncJob saved = githubSyncJobRepository.save(job);

        assertThat(saved.getId()).isNotNull();

        GithubSyncJob found = githubSyncJobRepository.findBySubmissionId(1001L).orElseThrow();
        assertThat(found.getProblemId()).isEqualTo(201L);
        assertThat(found.getStatus()).isEqualTo(GithubSyncJobStatus.PENDING);
    }

    @Test
    @DisplayName("동일한 submissionId로 중복 저장 시 uk_github_sync_jobs_submission 제약 위반 예외가 발생한다")
    void duplicateSubmissionIdThrowsException() {
        GithubSyncJob job1 = GithubSyncJob.createPending(connection, 1001L, 201L, "두 수의 합", 1);
        githubSyncJobRepository.saveAndFlush(job1);

        GithubSyncJob job2 = GithubSyncJob.createPending(connection, 1001L, 202L, "두 수의 합", 1);
        assertThatThrownBy(() -> githubSyncJobRepository.saveAndFlush(job2))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("선점 대상(PENDING, RETRY_WAITING, RATE_LIMITED) 작업을 SKIP LOCKED 쿼리로 조회할 수 있다")
    void findClaimableJobsForUpdateSkipLocked() {
        LocalDateTime now = LocalDateTime.now();

        // 1. PENDING (즉시 실행 대상)
        GithubSyncJob job1 = GithubSyncJob.createPending(connection, 1001L, 201L, "두 수의 합", 1);

        // 2. RETRY_WAITING (재시도 시각 도래)
        GithubSyncJob job2 = GithubSyncJob.createPending(connection, 1002L, 202L, "두 수의 합", 1);
        job2.markRetryWaiting(now.minusSeconds(10), "Temporary error");

        // 3. RETRY_WAITING (미래 재시도 - 제외되어야 함)
        GithubSyncJob job3 = GithubSyncJob.createPending(connection, 1003L, 203L, "두 수의 합", 1);
        job3.markRetryWaiting(now.plusMinutes(5), "Temporary error");

        // 4. RATE_LIMITED (해제 시각 도래)
        GithubSyncJob job4 = GithubSyncJob.createPending(connection, 1004L, 204L, "두 수의 합", 1);
        job4.markRateLimited(now.minusSeconds(5));

        // 5. SUCCEEDED (완료됨 - 제외)
        GithubSyncJob job5 = GithubSyncJob.createPending(connection, 1005L, 205L, "두 수의 합", 1);
        job5.markSucceeded("sha-12345");

        githubSyncJobRepository.saveAllAndFlush(List.of(job1, job2, job3, job4, job5));

        List<GithubSyncJob> claimable = githubSyncJobRepository.findClaimableJobsForUpdateSkipLocked(now, 10);

        assertThat(claimable).extracting(GithubSyncJob::getSubmissionId).containsExactlyInAnyOrder(1001L, 1002L, 1004L);
    }

    @Test
    @DisplayName("연동 ID와 상태 목록으로 작업을 조회할 수 있다")
    void findByConnectionIdAndStatusIn() {
        GithubSyncJob job1 = GithubSyncJob.createPending(connection, 1001L, 201L, "두 수의 합", 1);
        GithubSyncJob job2 = GithubSyncJob.createPending(connection, 1002L, 202L, "두 수의 합", 1);
        job2.markRunning();
        GithubSyncJob job3 = GithubSyncJob.createPending(connection, 1003L, 203L, "두 수의 합", 1);
        job3.markSucceeded("sha");

        githubSyncJobRepository.saveAllAndFlush(List.of(job1, job2, job3));

        List<GithubSyncJob> activeJobs
            = githubSyncJobRepository
                .findByConnectionIdAndStatusIn(
                    connection.getId(),
                    List.of(GithubSyncJobStatus.PENDING, GithubSyncJobStatus.RUNNING)
                );

        assertThat(activeJobs)
            .hasSize(2)
            .extracting(GithubSyncJob::getSubmissionId)
            .containsExactlyInAnyOrder(1001L, 1002L);
    }
}
