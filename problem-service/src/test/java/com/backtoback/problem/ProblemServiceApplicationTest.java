package com.backtoback.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.global.lock.RedisTransactionLock;
import com.backtoback.problem.testfixture.lock.LockTransactionProbe;
import com.backtoback.problem.testfixture.lock.LockTransactionProbeRepository;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/** 문제 서비스의 DB·Redis·S3 연결과 실제 잠금 예외·롤백 경계를 확인한다. */
@SpringBootTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/testmigration")
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Import(ProblemServiceApplicationTest.LockTimeoutController.class)
class ProblemServiceApplicationTest {
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("problem_db");

    static final LocalStackContainer LOCALSTACK
        = new LocalStackContainer("localstack/localstack:3.8").withServices("s3");

    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @Autowired
    private S3Client s3;

    @Autowired
    private MockMvc mvc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private LockTransactionProbeRepository probes;

    @Autowired
    private RedisTransactionLock transactionLock;

    @Autowired
    private RedissonClient redisson;

    @BeforeEach
    void prepareLockProbe() {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            jdbc.update("DELETE FROM lock_transaction_probe");
            probes.saveAndFlush(LockTransactionProbe.create(1L, "lock-target"));
        });
    }

    @BeforeAll
    static void startInfrastructure() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker가 없어 인프라 통합 테스트를 건너뜁니다.");
        MYSQL.start();
        LOCALSTACK.start();
        REDIS.start();
    }

    @AfterAll
    static void stopInfrastructure() {
        REDIS.stop();
        LOCALSTACK.stop();
        MYSQL.stop();
    }

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("LOCALSTACK_ENDPOINT", LOCALSTACK::getEndpoint);
        registry.add("spring.cloud.aws.region.static", LOCALSTACK::getRegion);
        registry.add("spring.cloud.aws.credentials.access-key", LOCALSTACK::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", LOCALSTACK::getSecretKey);
    }

    @Test
    @DisplayName("문제 데이터베이스에 연결하고 마이그레이션을 적용한다")
    void applicationConnectsToProblemDatabaseAndAppliesMigrations() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("problem_db");
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM setup_probe", Integer.class)).isZero();
    }

    @Test
    @DisplayName("설정된 S3 클라이언트로 LocalStack 객체를 저장하고 조회한다")
    void configuredS3ClientCanWriteAndReadLocalStack() {
        // given
        String bucket = "problem-setup-test";
        s3.createBucket(request -> request.bucket(bucket));

        // when
        s3.putObject(request -> request.bucket(bucket).key("probe"), RequestBody.fromString("connected"));

        // then
        assertThat(s3.getObjectAsBytes(request -> request.bucket(bucket).key("probe")).asUtf8String())
            .isEqualTo("connected");
    }

    @Test
    @DisplayName("인증 없이 서비스 상태를 조회한다")
    void healthIsAvailableWithoutAuthentication() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    @DisplayName("DB 쓰기를 flush한 뒤 Redis 잠금 소유권을 잃으면 실제 트랜잭션을 롤백한다")
    void lostRedisLockRollsBackFlushedDatabaseWrite() {
        long probeId = newProbeId();
        String lockKey = "problem:test:transaction-lock:" + UUID.randomUUID();
        var transaction = new TransactionTemplate(transactionManager);

        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> {
            probes.saveAndFlush(LockTransactionProbe.create(probeId, "lost-lock"));
            assertThat(transactionLock.acquire(lockKey)).isTrue();
            assertThat(redisson.getLock(lockKey).forceUnlock()).isTrue();
        })).isInstanceOf(ProblemException.class);

        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM lock_transaction_probe WHERE id = ?", Integer.class, probeId)
        ).isZero();
    }

    @Test
    @DisplayName("실제 MySQL JPA 잠금 대기 시간 초과는 409로 변환하고 앞선 쓰기를 롤백한다")
    void jpaLockTimeoutReturnsConflictAndRollsBackEarlierWrite() throws Exception {
        long probeId = newProbeId();
        String payload = "lock-timeout-" + UUID.randomUUID();
        var releaseLock = new CountDownLatch(1);
        var acquiredLock = new CountDownLatch(1);
        var transaction = new TransactionTemplate(transactionManager);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var blocker = executor.submit(() -> transaction.executeWithoutResult(status -> {
                probes.findForUpdate(1L);
                acquiredLock.countDown();
                try {
                    if (!releaseLock.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out waiting to release the probe row lock");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("Interrupted while holding the probe row lock", exception);
                }
            }));
            try {
                assertThat(acquiredLock.await(5, TimeUnit.SECONDS)).isTrue();
                mvc
                    .perform(
                        post("/api/v1/test/lock-timeout")
                            .param("id", Long.toString(probeId))
                            .param("payload", payload)
                            .header("X-User-Id", "12")
                            .header("X-User-Role", "USER")
                    )
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value(ProblemErrorCode.PROBLEM_VERSION_CONFLICT.getCode()));
            } finally {
                releaseLock.countDown();
            }

            blocker.get(10, TimeUnit.SECONDS);
            assertThat(
                jdbc.queryForObject("SELECT COUNT(*) FROM lock_transaction_probe WHERE id = ?", Integer.class, probeId)
            ).isZero();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lock_transaction_probe WHERE id = 1", Integer.class))
                .isEqualTo(1);
        }
    }

    private static long newProbeId() {
        long id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
        return id <= 1 ? id + 2 : id;
    }

    /** 실제 JPA 잠금 예외를 웹 예외 처리기까지 전달하는 통합 테스트 전용 경로다. */
    @RestController
    static class LockTimeoutController {
        private final JdbcTemplate jdbc;
        private final LockTransactionProbeRepository probes;

        LockTimeoutController(JdbcTemplate jdbc, LockTransactionProbeRepository probes) {
            this.jdbc = jdbc;
            this.probes = probes;
        }

        /** DB 행을 먼저 쓴 뒤 실제 DB 행 잠금 경합을 일으킨다. */
        @PostMapping("/api/v1/test/lock-timeout")
        @Transactional
        public void writeBeforeWaitingForLock(@RequestParam long id, @RequestParam String payload) {
            probes.saveAndFlush(LockTransactionProbe.create(id, payload));
            jdbc.execute("SET SESSION innodb_lock_wait_timeout = 1");
            try {
                probes.findForUpdate(1L);
            } finally {
                jdbc.execute("SET SESSION innodb_lock_wait_timeout = DEFAULT");
            }
        }
    }

}
