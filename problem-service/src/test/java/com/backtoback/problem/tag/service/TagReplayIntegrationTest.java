package com.backtoback.problem.tag.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.event.service.OutboxRelay;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.tag.dto.request.TagCreateRequest;

/** 실제 저장된 멱등 기록의 재응답이 공통 Redis 잠금과 분리되는지 검증한다. */
@SpringBootTest(
    properties = {
        "problem.messaging.validation-queue-url=http://localhost:4566/000000000000/problem-validation-queue",
        "spring.flyway.locations=classpath:db/migration,classpath:db/testmigration",
        "spring.cloud.aws.region.static=ap-northeast-2",
        "spring.cloud.aws.credentials.access-key=test",
        "spring.cloud.aws.credentials.secret-key=test"
    }
)
@Testcontainers(disabledWithoutDocker = true)
class TagReplayIntegrationTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("problem_db");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @MockitoBean
    private OutboxRelay relay;
    @Autowired
    private TagService service;
    @Autowired
    private TagCreationRecordService records;
    @Autowired
    private RedissonClient redisson;
    @Autowired
    private JdbcTemplate jdbc;

    /**
     * 실제 DB와 Redis 연결을 등록한다.
     *
     * @param registry 속성 등록기
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    /**
     * 다른 스레드의 공통 잠금이 유지되는 동안 완료 요청을 재응답한다.
     *
     * @throws Exception 비동기 재요청 실패 또는 시간 초과
     */
    @Test
    @DisplayName("다른 태그 변경이 공통 잠금을 보유해도 완료된 요청은 최초 응답을 반환한다")
    void replaysWhileSharedLockIsHeld() throws Exception {
        String key = UUID.randomUUID().toString();
        var request = new TagCreateRequest("태그-" + key, 0);
        var first = service.create("admin", key, request);
        var lock = redisson.getLock("problem:tags:order:lock");
        lock.lock();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var replay = executor.submit(() -> service.create("admin", key, request));
            assertThat(replay.get(5, TimeUnit.SECONDS)).isEqualTo(first);
            assertThat(lock.isHeldByCurrentThread()).isTrue();
        } finally {
            lock.unlock();
        }
    }

    /** 만료 여부와 원본 본문을 확인해 잘못된 최초 응답 재사용을 막는다. */
    @Test
    @DisplayName("재조회는 다른 본문을 거절하고 만료된 요청은 재사용하지 않는다")
    void validatesReplayRecord() {
        String key = UUID.randomUUID().toString();
        String name = "태그-" + key;
        service.create("admin", key, new TagCreateRequest(name, 0));
        assertThatThrownBy(() -> records.replay("admin", key, name, 1))
            .isInstanceOfSatisfying(
                ProblemException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT)
            );
        jdbc.update("UPDATE tag_creation_records SET expires_at='2000-01-01 00:00:00' WHERE idempotency_key=?", key);
        assertThat(records.replay("admin", key, name, 0)).isEmpty();
        assertThat(records.replay("admin", "missing", name, 0)).isEmpty();
    }
}
