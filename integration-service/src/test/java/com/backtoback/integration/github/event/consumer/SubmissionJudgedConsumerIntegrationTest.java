package com.backtoback.integration.github.event.consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.integration.github.domain.GithubConnection;
import com.backtoback.integration.github.repository.GithubConnectionRepository;
import com.backtoback.integration.github.repository.GithubSyncJobRepository;
import com.backtoback.integration.infra.event.repository.ProcessedEventRepository;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class SubmissionJudgedConsumerIntegrationTest {

    private static final String TEST_SECRET_KEY_BASE64 = "MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("integration_db");

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("integration.crypto.secret-key", () -> TEST_SECRET_KEY_BASE64);
        registry.add("infra.outbox.relay-initial-delay-ms", () -> "3600000");
        registry.add("infra.outbox.relay-fixed-delay-ms", () -> "3600000");
    }

    @Autowired
    private SubmissionJudgedConsumer consumer;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private GithubSyncJobRepository syncJobRepository;

    @Autowired
    private GithubConnectionRepository connectionRepository;

    @BeforeEach
    void setUp() {
        syncJobRepository.deleteAll();
        processedEventRepository.deleteAll();
        connectionRepository.deleteAll();

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
                .repositoryName("algo-repo")
                .autoCommitEnabled(true)
                .commitPathRule("{난이도}/{문제번호}-{문제명}/풀이.java")
                .build();
        connectionRepository.save(connection);
    }

    @Test
    @DisplayName("동일 eventId 및 동일 submissionId를 가진 중복 이벤트가 연속 수신되어도 rollback-only 예외 없이 정상 처리된다")
    void consumeDuplicateEventWithoutRollbackOnlyException() {
        String eventPayload = """
            {
                "eventId": "evt-dup-100",
                "eventType": "SubmissionJudged",
                "correlationId": "corr-100",
                "submissionId": "1001",
                "userId": "1",
                "problemId": "2001",
                "problemTitle": "두 수의 합",
                "problemDifficulty": 1,
                "verdict": "AC"
            }
            """;

        // 1회차 수신: 정상 처리 (ProcessedEvent 저장 + GithubSyncJob 생성)
        assertThatCode(() -> consumer.consume(eventPayload)).doesNotThrowAnyException();
        assertThat(processedEventRepository.existsByEventId("evt-dup-100")).isTrue();
        assertThat(syncJobRepository.existsBySubmissionId(1001L)).isTrue();

        // 2회차 수신: 중복 이벤트 멱등 무시 (트랜잭션 rollback-only 예외 없이 정상 종료)
        assertThatCode(() -> consumer.consume(eventPayload)).doesNotThrowAnyException();
    }
}
