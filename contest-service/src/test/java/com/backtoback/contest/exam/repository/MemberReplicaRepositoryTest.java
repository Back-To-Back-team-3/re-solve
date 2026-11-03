package com.backtoback.contest.exam.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * MySQL에서 회원 복제본의 버전 비교와 익명화를 검증한다.
 */
@SpringBootTest(
    properties = {
        "spring.cloud.aws.sns.enabled=false",
        "spring.cloud.aws.sqs.enabled=false"
    }
)
@Testcontainers(disabledWithoutDocker = true)
class MemberReplicaRepositoryTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("contest_db");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private MemberReplicaRepository members;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @DisplayName("회원 복제본은 더 최신 버전만 반영하고 탈퇴 시 표시 정보를 익명화한다")
    void preservesMemberVersionAndAnonymizesWithdrawal() {
        var now = LocalDateTime.of(2026, 1, 1, 0, 0);
        members.sync(98705L, "이전", "old", "SUSPENDED", 1L, now);
        members.sync(98705L, "현재", "new", "ACTIVE", 3L, now.plusSeconds(1));
        members.sync(98705L, "지연", "late", "SUSPENDED", 2L, now.plusSeconds(2));
        var active = jdbc.queryForMap("SELECT * FROM member_replicas WHERE member_id = ?", 98705L);
        assertThat(active.get("nickname")).isEqualTo("현재");
        assertThat(active.get("member_status")).isEqualTo("ACTIVE");
        assertThat(((Number)active.get("source_version")).longValue()).isEqualTo(3L);

        members.sync(98705L, "현재", "new", "WITHDRAWN", 4L, now.plusSeconds(3));
        members.sync(98705L, "복원", "late", "ACTIVE", 3L, now.plusSeconds(4));
        var withdrawn = jdbc.queryForMap("SELECT * FROM member_replicas WHERE member_id = ?", 98705L);
        assertThat(withdrawn.get("nickname")).isEqualTo("탈퇴한 사용자");
        assertThat(withdrawn.get("profile_image_url")).isNull();
        assertThat(withdrawn.get("member_status")).isEqualTo("WITHDRAWN");
    }

    @Test
    @DisplayName("같은 회원의 동시 최초 동기화는 더 큰 원천 버전을 보존한다")
    void concurrentFirstSnapshotsPreserveNewerVersion() throws Exception {
        var now = LocalDateTime.of(2026, 1, 1, 0, 0);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var oldSnapshot = executor.submit(() -> {
                start.await();
                members.sync(98706L, "이전", null, "ACTIVE", 1L, now);
                return null;
            });
            var newSnapshot = executor.submit(() -> {
                start.await();
                members.sync(98706L, "최신", null, "SUSPENDED", 2L, now);
                return null;
            });
            start.countDown();
            oldSnapshot.get(10, TimeUnit.SECONDS);
            newSnapshot.get(10, TimeUnit.SECONDS);
        }
        var row = jdbc.queryForMap("SELECT * FROM member_replicas WHERE member_id = ?", 98706L);
        assertThat(row.get("nickname")).isEqualTo("최신");
        assertThat(row.get("member_status")).isEqualTo("SUSPENDED");
        assertThat(((Number)row.get("source_version")).longValue()).isEqualTo(2L);
    }
}
