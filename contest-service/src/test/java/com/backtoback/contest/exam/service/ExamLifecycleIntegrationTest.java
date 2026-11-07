package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import net.javacrumbs.shedlock.core.LockConfiguration;
import net.javacrumbs.shedlock.core.LockProvider;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.scheduling.config.SchedulerLockConfiguration;
import com.backtoback.common.time.ClockConfiguration;

/** MySQL의 부모 잠금과 Outbox 트랜잭션 및 JDBC ShedLock을 확인한다. */
@SpringBootTest(
    properties = {
        "spring.cloud.aws.sns.enabled=false",
        "spring.cloud.aws.sqs.enabled=false"
    }
)
@Import(SchedulerLockConfiguration.class)
@Testcontainers(disabledWithoutDocker = true)
class ExamLifecycleIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("contest_db");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    /** 테스트 컨텍스트에 격리된 DB와 Redis 연결을 등록한다. */
    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @MockitoBean
    private ExamLifecycleScheduler scheduler;
    @MockitoSpyBean
    private OutboxEventWriter outbox;
    @Autowired
    private ExamLifecycleService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private LockProvider lockProvider;
    private long examId;

    /** 시작과 종료가 지난 시험과 입장하지 않은 등록자를 저장한다. */
    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM exam_participant_histories");
        jdbc.update("DELETE FROM exam_participants");
        jdbc.update("DELETE FROM exams");
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc
            .update(
                "INSERT INTO exams (study_id,creator_id,title,mode,starts_at,ends_at,status,exam_revision,"
                    + "total_score,result_revision,version,created_at,updated_at) VALUES "
                    + "(12,7,'시험','FIXED',?,?,'SCHEDULED',1,100,0,0,?,?)",
                now.minusHours(2),
                now.minusHours(1),
                now,
                now
            );
        examId = jdbc.queryForObject("SELECT MAX(id) FROM exams", Long.class);
        jdbc
            .update(
                "INSERT INTO exam_participants (exam_id,member_id,status,participant_revision,registered_at,"
                    + "personal_ends_at,total_score,is_ranked,version,created_at,updated_at) "
                    + "VALUES (?,7,'REGISTERED',1,?,?,0,false,0,?,?)",
                examId,
                now.minusHours(3),
                now.minusHours(1),
                now,
                now
            );
    }

    /** 같은 시험의 동시 전이는 부모 잠금으로 직렬화해 이벤트를 한 번만 저장한다. */
    @Test
    @DisplayName("지연된 시험을 동시에 처리해도 시작·종료 이벤트와 결시 이력은 한 번씩 저장한다")
    void concurrentTransitions() throws Exception {
        // given
        var start = new CountDownLatch(1);
        // when
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                service.transition(examId);
                return null;
            });
            var second = pool.submit(() -> {
                start.await();
                service.transition(examId);
                return null;
            });
            start.countDown();
            first.get(10, TimeUnit.SECONDS);
            second.get(10, TimeUnit.SECONDS);
        }
        service.transition(examId);
        // then
        assertThat(jdbc.queryForObject("SELECT status FROM exams WHERE id=?", String.class, examId))
            .isEqualTo("CLOSED");
        assertThat(jdbc.queryForObject("SELECT status FROM exam_participants WHERE exam_id=?", String.class, examId))
            .isEqualTo("ABSENT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_participant_histories", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(2);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM exam_participant_histories WHERE actor_member_id IS NULL",
                    Integer.class
                )
        ).isEqualTo(1);
    }

    /** 종료 이벤트 저장에 실패하면 시작 이벤트와 모든 상태 변경도 롤백한다. */
    @Test
    @DisplayName("종료 Outbox 저장 실패는 시작 전이와 결시 이력까지 롤백한다")
    void rollsBackBothTransitions() {
        // given
        doThrow(new IllegalStateException("outbox failure"))
            .when(outbox)
            .append(eq("EXAM"), eq("ExamClosed"), eq("v1"), any(), any(), any());
        // when & then
        assertThatThrownBy(() -> service.transition(examId)).isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM exams WHERE id=?", String.class, examId))
            .isEqualTo("SCHEDULED");
        assertThat(jdbc.queryForObject("SELECT status FROM exam_participants WHERE exam_id=?", String.class, examId))
            .isEqualTo("REGISTERED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_participant_histories", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
    }

    /** 부모 잠금 대기 중 설정이 바뀌면 획득 후 새로운 시작 시각을 기준으로 판단한다. */
    @Test
    @DisplayName("설정 변경이 먼저 커밋되면 대기한 전이는 변경된 미래 시작 시각을 따른다")
    void rechecksAfterParentLock() throws Exception {
        // given
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        // when & then
        try (var pool = Executors.newFixedThreadPool(2)) {
            var update = pool.submit(() -> transactions.executeWithoutResult(status -> {
                var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
                jdbc
                    .update(
                        "UPDATE exams SET starts_at=?, ends_at=? WHERE id=?",
                        now.plusHours(1),
                        now.plusHours(2),
                        examId
                    );
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var transition = pool.submit(() -> service.transition(examId));
            try {
                assertThatThrownBy(() -> transition.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            update.get(5, TimeUnit.SECONDS);
            transition.get(5, TimeUnit.SECONDS);
        }
        assertThat(jdbc.queryForObject("SELECT status FROM exams WHERE id=?", String.class, examId))
            .isEqualTo("SCHEDULED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
    }

    /** 같은 이름의 JDBC 잠금은 첫 보유자가 해제할 때까지 다시 획득할 수 없다. */
    @Test
    @DisplayName("동일한 스케줄러 잠금은 다른 실행이 보유 중이면 획득하지 못한다")
    void lockPreventsConcurrentExecution() {
        // given
        var config
            = new LockConfiguration(Instant.now(), "ExamLifecycleScheduler", Duration.ofSeconds(30), Duration.ZERO);
        var first = lockProvider.lock(config).orElseThrow();
        try {
            // when & then
            assertThat(lockProvider.lock(config)).isEmpty();
        } finally {
            first.unlock();
        }
    }
}
