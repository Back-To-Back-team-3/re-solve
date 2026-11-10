package com.backtoback.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.LocalDateTime;
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
import org.springframework.scheduling.TaskScheduler;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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

import com.backtoback.common.event.service.OutboxRelay;
import com.backtoback.common.scheduling.scheduler.OutboxCleanupScheduler;
import com.backtoback.common.scheduling.scheduler.ProcessedEventCleanupScheduler;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.global.lock.RedisTransactionLock;
import com.backtoback.problem.problem.service.ProblemAssetContent;
import com.backtoback.problem.problem.service.ProblemAssetReuseService;
import com.backtoback.problem.problem.service.ProblemAssetUploadService;
import com.backtoback.problem.testfixture.lock.LockTransactionProbe;
import com.backtoback.problem.testfixture.lock.LockTransactionProbeRepository;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/** 문제 서비스의 DB·Redis·S3 연결과 실제 잠금 예외·롤백 경계를 확인한다. */
@MockitoBean(types = OutboxRelay.class)
@SpringBootTest(
    properties = {
        "spring.flyway.locations=classpath:db/migration,classpath:db/testmigration",
        "problem.messaging.validation-queue-url=http://localhost:4566/000000000000/problem-validation-queue"
    }
)
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
    private ProblemAssetUploadService assetUpload;

    @Autowired
    private ProblemAssetReuseService assetReuse;

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

    @Autowired
    private Clock clock;

    @Autowired
    private OutboxCleanupScheduler outboxCleanup;

    @Autowired
    private ProcessedEventCleanupScheduler processedCleanup;

    @Autowired
    private TaskScheduler taskScheduler;

    /**
     * 네 정리 작업이 실행 중이어도 예약한 발행 작업을 시작할 수 있는지 확인한다.
     *
     * @throws InterruptedException 예약 작업을 기다리는 중 인터럽트된 경우
     */
    @Test
    @DisplayName("모든 정리 작업이 실행 중이어도 Outbox 발행은 시작할 수 있다")
    void schedulerCanStartRelayWhileCleanupRuns() throws InterruptedException {
        var cleanupStarted = new CountDownLatch(4);
        var finishCleanup = new CountDownLatch(1);
        var relayStarted = new CountDownLatch(1);
        try {
            for (int job = 0; job < 4; job++) {
                taskScheduler.schedule(() -> {
                    cleanupStarted.countDown();
                    try {
                        finishCleanup.await();
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                }, clock.instant());
            }
            assertThat(cleanupStarted.await(5, TimeUnit.SECONDS)).isTrue();

            taskScheduler.schedule(relayStarted::countDown, clock.instant());

            assertThat(relayStarted.await(5, TimeUnit.SECONDS)).isTrue();
        } finally {
            finishCleanup.countDown();
        }
    }

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

    /** 문제 DB의 최신 마이그레이션과 공통 감사 테이블 생성을 확인한다. */
    @Test
    @DisplayName("문제 DB와 ShedLock·감사 테이블을 포함한 Flyway 마이그레이션을 적용한다")
    void applicationConnectsToProblemDatabaseAndAppliesMigrations() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("problem_db");
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("25");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM setup_probe", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM shedlock WHERE name NOT IN ('OutboxCleanupScheduler', 'ProcessedEventCleanupScheduler')
            """, Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM problem_revision_tags", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM problem_state_operations", Integer.class)).isZero();
    }

    /** 실제 Problem DB에서 공통 스케줄러가 만료된 이벤트만 삭제하는지 검증한다. */
    @Test
    @DisplayName("발행·소비 완료 기록은 보관기간이 지난 것만 삭제하고 실패·대기 Outbox는 보존한다")
    void cleansExpiredEventRecords() {
        var now = LocalDateTime.ofInstant(clock.instant(), ClockConfiguration.SERVICE_ZONE);
        var old = now.minusDays(15);
        jdbc.update("""
            INSERT INTO outbox_events (
                event_id, event_type, schema_version, aggregate_type, aggregate_id, correlation_id,
                payload, status, retry_count, occurred_at, published_at, created_at, updated_at
            ) VALUES
                ('cleanup-old', 'ProblemValidationRequested', 'v1', 'PROBLEM', '1', 'cleanup', '{}',
                    'PUBLISHED', 0, ?, ?, ?, ?),
                ('cleanup-recent', 'ProblemValidationRequested', 'v1', 'PROBLEM', '1', 'cleanup', '{}',
                    'PUBLISHED', 0, ?, ?, ?, ?),
                ('cleanup-pending', 'ProblemValidationRequested', 'v1', 'PROBLEM', '1', 'cleanup', '{}',
                    'PENDING', 0, ?, NULL, ?, ?),
                ('cleanup-failed', 'ProblemValidationRequested', 'v1', 'PROBLEM', '1', 'cleanup', '{}',
                    'FAILED', 3, ?, NULL, ?, ?)
            """, old, old, old, old, old, now, old, now, old, old, old, old, old, old);
        jdbc.update("""
            INSERT INTO processed_events (event_id, event_type, processed_at, created_at)
            VALUES ('cleanup-old', 'ValidationCompleted', ?, ?),
                   ('cleanup-recent', 'ValidationCompleted', ?, ?)
            """, old, old, now, old);

        outboxCleanup.cleanup();
        processedCleanup.cleanup();

        assertThat(jdbc.queryForList("SELECT event_id FROM outbox_events WHERE correlation_id='cleanup'", String.class))
            .containsExactlyInAnyOrder("cleanup-recent", "cleanup-pending", "cleanup-failed");
        assertThat(
            jdbc.queryForList("SELECT event_id FROM processed_events WHERE event_id LIKE 'cleanup-%'", String.class)
        ).containsExactly("cleanup-recent");
    }

    @Test
    @DisplayName("시드 태그·문제·회차·본문 자산이 예상한 건수로 적용된다")
    void seedDataIsAppliedWithExpectedCounts() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags", Integer.class)).isEqualTo(10);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT display_order) FROM tags", Integer.class)).isEqualTo(10);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM problems WHERE scope = 'GENERAL' AND visibility = 'PUBLIC'",
                    Integer.class
                )
        ).isEqualTo(30);
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM problem_body_assets WHERE status = 'LINKED'", Integer.class)
        ).isEqualTo(30);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT function_spec) FROM problem_revisions", Integer.class))
            .isEqualTo(30);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM problem_revisions r " + "JOIN problems p ON r.problem_id = p.id "
                        + "JOIN problem_body_assets a ON r.body_asset_id = a.id AND a.problem_id = p.id",
                    Integer.class
                )
        ).isEqualTo(30);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT COUNT(DISTINCT problem_id) FROM problem_revisions WHERE revision_number = 1",
                    Integer.class
                )
        ).isEqualTo(30);
    }

    @Test
    @DisplayName("시드 테스트케이스 데이터가 예상한 건수·정합성으로 적용된다")
    void testCaseSeedDataIsAppliedWithExpectedCounts() {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM problem_test_assets", Integer.class)).isEqualTo(150);
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM problem_test_assets WHERE status = 'LINKED'", Integer.class)
        ).isEqualTo(150);
        assertThat(jdbc.queryForObject("SELECT COUNT(DISTINCT object_key) FROM problem_test_assets", Integer.class))
            .isEqualTo(150);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM problem_revision_tests", Integer.class)).isEqualTo(150);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM problem_revision_tests rt "
                        + "JOIN problem_revisions r ON rt.problem_revision_id = r.id "
                        + "JOIN problems p ON r.problem_id = p.id "
                        + "JOIN problem_test_assets a ON rt.test_asset_id = a.id AND a.problem_id = p.id",
                    Integer.class
                )
        ).isEqualTo(150);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT COUNT(DISTINCT problem_revision_id) FROM problem_revision_tests WHERE is_sample = TRUE",
                    Integer.class
                )
        ).isEqualTo(30);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT COUNT(DISTINCT problem_revision_id) FROM problem_revision_tests WHERE is_sample = FALSE",
                    Integer.class
                )
        ).isEqualTo(30);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM (SELECT problem_revision_id FROM problem_revision_tests "
                        + "GROUP BY problem_revision_id HAVING COUNT(*) = 5 AND SUM(is_sample) = 2) t",
                    Integer.class
                )
        ).isEqualTo(30);
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

    /** 실제 S3와 DB에서 불변 저장 및 같은 문제의 재사용 후보 조회를 확인한다. */
    @Test
    @DisplayName("버전 없는 버킷에 본문과 테스트를 저장하고 같은 문제에서 재사용한다")
    void uploadsAndReusesAssets() {
        // given
        String bucket = "problem-assets-" + UUID.randomUUID();
        s3.createBucket(request -> request.bucket(bucket));
        var content = ProblemAssetContent.fromBytes("원문".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Long bodyId = null;
        Long testId = null;
        try {
            // when
            bodyId = assetUpload.upload(bucket, 1L, content, true);
            testId = assetUpload.upload(bucket, 1L, content, false);
            jdbc.update("UPDATE problem_body_assets SET problem_id = 1, status = 'LINKED' WHERE id = ?", bodyId);
            jdbc.update("UPDATE problem_test_assets SET problem_id = 1, status = 'LINKED' WHERE id = ?", testId);

            // then
            assertThat(assetReuse.find(bucket, 1L, 1L, content, true)).contains(bodyId);
            assertThat(assetReuse.find(bucket, 1L, 1L, content, false)).contains(testId);
            assertThat(assetReuse.find(bucket, 2L, 1L, content, true)).isEmpty();
            assertThat(assetReuse.find(bucket, 1L, 2L, content, false)).isEmpty();
            assertThat(
                jdbc.queryForObject("SELECT object_version FROM problem_test_assets WHERE id = ?", String.class, testId)
            ).isNull();
        } finally {
            if (bodyId != null) {
                jdbc.update("DELETE FROM problem_body_assets WHERE id = ?", bodyId);
            }
            if (testId != null) {
                jdbc.update("DELETE FROM problem_test_assets WHERE id = ?", testId);
            }
        }
    }

    /** 업로드 완료 뒤 DB 작업이 롤백되어도 추적 기록과 실제 원문이 유지되는지 확인한다. */
    @Test
    @DisplayName("업로드 이후 DB 작업이 실패해도 본문과 테스트 원문을 보존한다")
    void preservesUploadedObjectsAfterDatabaseRollback() {
        // given
        String bucket = "problem-rollback-" + UUID.randomUUID();
        s3.createBucket(request -> request.bucket(bucket));
        byte[] bytes = "원문".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var content = ProblemAssetContent.fromBytes(bytes);
        Long bodyId = assetUpload.upload(bucket, 1L, content, true);
        Long testId = assetUpload.upload(bucket, 1L, content, false);
        String bodyKey
            = jdbc.queryForObject("SELECT object_key FROM problem_body_assets WHERE id = ?", String.class, bodyId);
        String testKey
            = jdbc.queryForObject("SELECT object_key FROM problem_test_assets WHERE id = ?", String.class, testId);
        try {
            // when
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbc.update("UPDATE problem_body_assets SET status = 'DELETING' WHERE id = ?", bodyId);
                jdbc.update("UPDATE problem_test_assets SET status = 'DELETING' WHERE id = ?", testId);
                status.setRollbackOnly();
            });

            // then
            assertThat(jdbc.queryForObject("SELECT status FROM problem_body_assets WHERE id = ?", String.class, bodyId))
                .isEqualTo("PENDING");
            assertThat(jdbc.queryForObject("SELECT status FROM problem_test_assets WHERE id = ?", String.class, testId))
                .isEqualTo("PENDING");
            assertThat(
                jdbc
                    .queryForObject(
                        "SELECT upload_finished_at FROM problem_body_assets WHERE id = ?",
                        java.time.LocalDateTime.class,
                        bodyId
                    )
            ).isNotNull();
            assertThat(
                jdbc
                    .queryForObject(
                        "SELECT upload_finished_at FROM problem_test_assets WHERE id = ?",
                        java.time.LocalDateTime.class,
                        testId
                    )
            ).isNotNull();
            assertThat(s3.getObjectAsBytes(request -> request.bucket(bucket).key(bodyKey)).asByteArray())
                .isEqualTo(bytes);
            assertThat(s3.getObjectAsBytes(request -> request.bucket(bucket).key(testKey)).asByteArray())
                .isEqualTo(bytes);
        } finally {
            jdbc.update("DELETE FROM problem_body_assets WHERE id = ?", bodyId);
            jdbc.update("DELETE FROM problem_test_assets WHERE id = ?", testId);
        }
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
