package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.service.ExamDraftStore.SaveRequest;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

/** MySQL 잠금과 Redis 버퍼로 Draft 순번과 재접속 복구를 확인한다. */
@SpringBootTest(
    properties = {
        "spring.cloud.aws.sns.enabled=false",
        "spring.cloud.aws.sqs.enabled=false"
    }
)
@Testcontainers(disabledWithoutDocker = true)
class ExamDraftIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("contest_db");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    /** 테스트 컨텍스트의 격리된 저장소 주소를 등록한다. */
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
    @MockitoBean
    private MemberReplicaService refresh;
    @Autowired
    private ExamDraftStore store;
    @Autowired
    private ExamDraftBuffer buffer;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private StringRedisTemplate redis;
    @Autowired
    private TransactionTemplate transactions;
    private String examId;
    private long participantId;

    /** 입장한 참가자와 두 문제를 저장한다. */
    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM exam_drafts");
        jdbc.update("DELETE FROM exam_participant_histories");
        jdbc.update("DELETE FROM exam_participants");
        jdbc.update("DELETE FROM exam_problems");
        jdbc.update("DELETE FROM exams");
        jdbc.update("DELETE FROM member_replicas");
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc
            .update(
                "INSERT INTO member_replicas (member_id,nickname,member_status,source_version,"
                    + "synced_at,created_at,updated_at) " + "VALUES (7,'회원','ACTIVE',1,?,?,?)",
                now,
                now,
                now
            );
        jdbc
            .update(
                "INSERT INTO exams (study_id,creator_id,title,mode,starts_at,ends_at,status,exam_revision,"
                    + "total_score,result_revision,version,created_at,updated_at) VALUES "
                    + "(12,7,'시험','FIXED',?,?,'IN_PROGRESS',1,100,0,0,?,?)",
                now.minusHours(1),
                now.plusHours(1),
                now,
                now
            );
        examId = jdbc.queryForObject("SELECT MAX(id) FROM exams", String.class);
        jdbc
            .update(
                "INSERT INTO exam_participants (exam_id,member_id,status,participant_revision,registered_at,entered_at,"
                    + "personal_ends_at,total_score,is_ranked,version,created_at,updated_at) "
                    + "VALUES (?,7,'STARTED',2,?,?,?,0,false,0,?,?)",
                examId,
                now.minusHours(2),
                now.minusMinutes(30),
                now.plusHours(1),
                now,
                now
            );
        participantId = jdbc.queryForObject("SELECT MAX(id) FROM exam_participants", Long.class);
        for (int problem = 101; problem <= 102; problem++) {
            jdbc
                .update(
                    "INSERT INTO exam_problems (exam_id,problem_id,problem_revision_id,problem_title,revision_number,"
                        + "score,display_order,created_at,updated_at) VALUES (?,?,?,'문제',1,50,?,?,?)",
                    examId,
                    problem,
                    problem + 900,
                    problem - 100,
                    now,
                    now
                );
        }
        given(refresh.refresh(7)).willReturn("ACTIVE");
    }

    /** 언어 전환 이후에도 순번은 문제 전체에서 비교하고 빈 최신 코드를 복구한다. */
    @Test
    @DisplayName("언어를 바꿔도 작은 순번을 무시하고 빈 최신 코드를 복구한다")
    void sequenceAcrossLanguages() {
        // given
        var first = store.prepare(examId, "7", "101", new SaveRequest("java", "코드", "5"));
        var older = store.prepare(examId, "7", "101", new SaveRequest("cpp", "이전", "4"));
        var equal = store.prepare(examId, "7", "101", new SaveRequest("python3", "동일", "5"));
        var empty = store.prepare(examId, "7", "101", new SaveRequest("cpp", "", "6"));
        // when
        assertThat(store.save(first).applied()).isTrue();
        var ignored = store.save(older);
        assertThat(store.save(equal).applied()).isFalse();
        assertThat(store.save(empty).applied()).isTrue();
        jdbc.update("UPDATE exam_participants SET status='FINISHED' WHERE id=?", participantId);
        var recovered = store.read(examId, "7");
        // then
        assertThat(ignored.applied()).isFalse();
        assertThat(ignored.serverSeq()).isEqualTo("5");
        assertThat(recovered.content()).extracting("sourceCode").containsExactlyInAnyOrder("코드", "");
        assertThat(recovered.problemSequences()).extracting("serverSeq").containsExactly("6", "0");
    }

    /** 동일 창의 뒤 요청만 저장하고 밀린 다른 문제는 0과 null을 반환한다. */
    @Test
    @DisplayName("같은 참가자의 버퍼에서 밀린 요청은 미저장 문제의 순번 0과 null 시각을 반환한다")
    void bufferKeepsLastRequest() throws Exception {
        // given
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> buffer.save(examId, "7", "102", new SaveRequest("java", "첫 코드", "1")));
            var end = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (redis.keys("exam:draft:buffer:" + participantId + ":slot:*").isEmpty()) {
                if (System.nanoTime() >= end) {
                    throw new IllegalStateException("Buffer not created");
                }
                Thread.sleep(10);
            }
            // when
            var last = pool.submit(() -> buffer.save(examId, "7", "101", new SaveRequest("java", "마지막", "1")));
            var skipped = first.get(5, TimeUnit.SECONDS);
            var saved = last.get(5, TimeUnit.SECONDS);
            // then
            assertThat(saved.applied()).isTrue();
            assertThat(skipped.applied()).isFalse();
            assertThat(skipped.serverSeq()).isEqualTo("0");
            assertThat(skipped.savedAt()).isNull();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_drafts", Integer.class)).isEqualTo(1);
        }
    }

    /** 참가자 잠금 대기 중 마감이 지나면 새 저장을 거절한다. */
    @Test
    @DisplayName("부모 잠금 대기 중 개인 마감이 바뀌면 저장하지 않는다")
    void rechecksDeadlineAfterLock() throws Exception {
        // given
        var work = store.prepare(examId, "7", "101", new SaveRequest("java", "코드", "1"));
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var update = pool.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.update("UPDATE exams SET version=version+1 WHERE id=?", examId);
                jdbc
                    .update(
                        "UPDATE exam_participants SET personal_ends_at=? WHERE id=?",
                        LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).minusSeconds(1),
                        participantId
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
            // when & then
            var save = pool.submit(() -> store.save(work));
            try {
                assertThatThrownBy(() -> save.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            update.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> save.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ContestException.class)
                .satisfies(
                    error -> assertThat(((ContestException)error.getCause()).getErrorCode())
                        .isEqualTo(ContestErrorCode.EXAM_CLOSED)
                );
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_drafts", Integer.class)).isZero();
    }

    /** 동시 언어 저장은 참가자 잠금으로 직렬화하고 최대 순번을 유지한다. */
    @Test
    @DisplayName("서로 다른 언어의 동시 저장은 큰 순번을 최신 Draft로 유지한다")
    void concurrentLanguagesKeepMaximum() throws Exception {
        // given
        var smaller = store.prepare(examId, "7", "101", new SaveRequest("java", "이전", "1"));
        var larger = store.prepare(examId, "7", "101", new SaveRequest("python3", "최신", "2"));
        var start = new CountDownLatch(1);
        // when
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                return store.save(smaller);
            });
            var second = pool.submit(() -> {
                start.await();
                return store.save(larger);
            });
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertThat(second.get(5, TimeUnit.SECONDS).applied()).isTrue();
        }
        // then
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT MAX(seq) FROM exam_drafts WHERE exam_participant_id=? AND problem_id=101",
                    Long.class,
                    participantId
                )
        ).isEqualTo(2L);
    }

    /** 정지된 회원의 신규 저장은 참가와 기존 저장본을 바꾸지 않는다. */
    @Test
    @DisplayName("정지 회원은 새 Draft를 저장할 수 없다")
    void suspendedCannotSave() {
        // given
        var work = store.prepare(examId, "7", "101", new SaveRequest("java", "코드", "1"));
        jdbc.update("UPDATE member_replicas SET member_status='SUSPENDED' WHERE member_id=7");
        // when & then
        assertThatThrownBy(() -> store.save(work))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.MEMBER_SUSPENDED)
            );
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_drafts", Integer.class)).isZero();
    }
}
