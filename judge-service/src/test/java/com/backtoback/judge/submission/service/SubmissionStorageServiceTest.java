package com.backtoback.judge.submission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.judge.global.config.PersistenceConfiguration;
import com.backtoback.judge.judging.domain.ExecutionRuntime;
import com.backtoback.judge.judging.domain.JudgeJob;
import com.backtoback.judge.judging.domain.JudgeJobRun;
import com.backtoback.judge.judging.domain.JudgeTestResult;
import com.backtoback.judge.judging.repository.ExecutionRuntimeRepository;
import com.backtoback.judge.judging.repository.JudgeJobRepository;
import com.backtoback.judge.judging.repository.JudgeJobRunRepository;
import com.backtoback.judge.judging.repository.JudgeTestResultRepository;
import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;
import com.backtoback.judge.submission.domain.Submission;
import com.backtoback.judge.submission.domain.SubmissionDraft;
import com.backtoback.judge.submission.repository.SubmissionRepository;
import com.backtoback.judge.submission.service.dto.StoredSubmission;
import com.backtoback.judge.submission.service.dto.SubmissionStorageRequest;
import com.backtoback.judge.submission.service.dto.SubmissionStorageRequest.TestCase;

/** 실제 MySQL과 Flyway에서 저장 트랜잭션·제약·감사 시각·실행 환경 동시 확정을 검증한다. */
@DataJpaTest(
    properties = {
        "spring.jpa.hibernate.ddl-auto=validate",
        "spring.jpa.show-sql=false"
    }
)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    {
        PersistenceConfiguration.class,
        SubmissionStorageService.class,
        SubmissionStorageServiceTest.TimeConfiguration.class
    }
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class SubmissionStorageServiceTest {
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-10-27T09:00:00.123456");
    private static final JudgeRuntime JAVA = new JudgeRuntime("java", 91, "Java (OpenJDK 17.0.6)", "17.0.6", null);
    private static final List<TestCase> TESTS = List.of(new TestCase(501, 1, true), new TestCase(502, 2, false));
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("judge_storage");
    @Autowired
    private SubmissionStorageService storage;
    @Autowired
    private SubmissionRepository submissions;
    @Autowired
    private JudgeJobRepository jobs;
    @Autowired
    private JudgeTestResultRepository tests;
    @Autowired
    private JudgeJobRunRepository runs;
    @Autowired
    private ExecutionRuntimeRepository runtimes;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Flyway flyway;
    @Autowired
    private PlatformTransactionManager transactionManager;

    /**
     * 전용 MySQL 컨테이너를 테스트 데이터 소스로 연결한다.
     *
     * @param registry 동적 테스트 속성
     */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /** 테스트 간 FK 순서에 맞춰 데이터를 정리한다. */
    @BeforeEach
    void clearRows() {
        jdbc.update("DELETE FROM judge_test_results");
        jdbc.update("DELETE FROM judge_job_runs");
        jdbc.update("DELETE FROM judge_jobs");
        jdbc.update("DELETE FROM submissions");
        jdbc.update("DELETE FROM execution_runtimes");
    }

    /** 최초 저장이 문서의 대기 상태와 원본·감사 시각을 보존하는지 검증한다. */
    @Test
    @DisplayName("Flyway와 Hibernate 검증을 통과하고 제출·작업·공개/숨김 결과 자리를 함께 저장한다")
    void registersPendingGraph() {
        var saved = storage.register(request("request-1", JAVA));
        var submission = submissions.findById(saved.submissionId()).orElseThrow();
        var job = jobs.findBySubmissionIdAndJudgeAttempt(saved.submissionId(), saved.judgeAttempt()).orElseThrow();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("9");
        assertThat(submission.getStatus()).isEqualTo(Submission.Status.QUEUED);
        assertThat(submission.getVerdict()).isNull();
        assertThat(submission.getJudgeAttempt()).isEqualTo(1);
        assertThat(submission.getProblemRevisionId()).isEqualTo(1001);
        assertThat(submission.getSourceCode()).isEqualTo("return \"가\";");
        assertThat(submission.getCodeSizeBytes()).isEqualTo(13);
        assertThat(submission.getSubmittedAt()).isEqualTo(NOW.minusSeconds(1));
        assertThat(submission.getCreatedAt()).isEqualTo(NOW);
        assertThat(submission.getUpdatedAt()).isEqualTo(NOW);
        assertThat(job.getSubmission().getId()).isEqualTo(submission.getId());
        assertThat(job.getStatus()).isEqualTo(JudgeJob.Status.PENDING);
        assertThat(job.getJudgeAttempt()).isEqualTo(1);
        assertThat(job.getTryCount()).isEqualTo(1);
        assertThat(job.getNextRunAt()).isEqualTo(NOW);
        assertThat(job.getDeadlineAt()).isEqualTo(NOW.plusMinutes(10));
        assertThat(job.getLeaseToken()).isNull();
        assertThat(job.getLeaseUntil()).isNull();
        assertThat(job.getStartedAt()).isNull();
        assertThat(job.getFinishedAt()).isNull();
        assertThat(tests.findByJudgeJobIdOrderByTestOrder(job.getId()))
            .extracting(JudgeTestResult::getTestOrder)
            .containsExactly(1, 2);
        assertThat(tests.findByJudgeJobIdOrderByTestOrder(job.getId())).allSatisfy(test -> {
            assertThat(test.getTokenStatus()).isEqualTo(JudgeTestResult.TokenStatus.NOT_CREATED);
            assertThat(test.getJudge0Token()).isNull();
            assertThat(test.getCreatedTryCount()).isNull();
            assertThat(test.getVerdict()).isNull();
            assertThat(test.getActualOutput()).isNull();
            assertThat(test.getTimeMs()).isNull();
            assertThat(test.getMemoryKb()).isNull();
        });
        assertThat(runs.count()).isZero();
        assertThat(runtimes.findById(submission.getRuntime().getId()).orElseThrow().toRuntime()).isEqualTo(JAVA);
    }

    /** 같은 환경을 공유하고 이름·버전·옵션 중 하나라도 다르면 새 스냅샷을 만든다. */
    @Test
    @DisplayName("실행 환경 ID만 비교하지 않고 모든 스냅샷 속성과 NULL 옵션까지 비교한다")
    void reusesExactRuntimeSnapshot() {
        var first = storage.register(request("first", JAVA));
        var second = storage.register(request("second", JAVA));
        assertThat(runtimeId(first.submissionId())).isEqualTo(runtimeId(second.submissionId()));
        int index = 0;
        for (var selected : List
            .of(
                new JudgeRuntime("java", 91, "renamed", "17.0.6", null),
                new JudgeRuntime("java", 91, JAVA.runtimeName(), "17.0.7", null),
                new JudgeRuntime("java", 91, JAVA.runtimeName(), "17.0.6", ""),
                new JudgeRuntime("java", 91, JAVA.runtimeName(), "17.0.6", "-g")
            )) {
            storage.register(request("runtime-" + ++index, selected));
        }
        assertThat(runtimes.count()).isEqualTo(5);
    }

    /** 늦은 환경 선택은 저장한 참조를 덮어쓰지 않는다. */
    @Test
    @DisplayName("미확정 제출의 실행 환경을 한 번만 연결하고 재호출에는 기존 환경을 반환한다")
    void fixesRuntimeOnlyOnce() {
        var saved = storage.register(request("unfixed", null));
        assertThat(runtimeId(saved.submissionId())).isNull();
        assertThat(storage.fixRuntime(saved.submissionId(), JAVA)).isEqualTo(JAVA);
        var changed = new JudgeRuntime("java", 999, "Java (OpenJDK 17.0.9)", "17.0.9", null);
        assertThat(storage.fixRuntime(saved.submissionId(), changed)).isEqualTo(JAVA);
        assertThat(runtimes.count()).isEqualTo(1);
        var otherLanguage = new JudgeRuntime("python3", 71, "Python (3.11.2)", "3.11.2", null);
        var unfixed = storage.register(request("wrong-language", null));
        assertThatThrownBy(() -> storage.fixRuntime(unfixed.submissionId(), otherLanguage))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(runtimeId(unfixed.submissionId())).isNull();
        assertThat(runtimes.count()).isEqualTo(1);
    }

    /**
     * 서로 다른 실행 환경 후보가 경쟁해도 한 제출은 같은 환경으로 확정한다.
     *
     * @throws Exception 동시 실행이나 결과 조회가 실패한 경우
     */
    @Test
    @DisplayName("실행 환경 동시 확정은 제출 행 잠금으로 직렬화한다")
    void serializesConcurrentRuntimeSelection() throws Exception {
        var saved = storage.register(request("concurrent", null));
        var changed = new JudgeRuntime("java", 999, "Java (OpenJDK 17.0.9)", "17.0.9", null);
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return storage.fixRuntime(saved.submissionId(), JAVA);
            });
            var second = executor.submit(() -> {
                start.await();
                return storage.fixRuntime(saved.submissionId(), changed);
            });
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(second.get(10, TimeUnit.SECONDS));
        }
        assertThat(runtimes.count()).isEqualTo(1);
    }

    /** 상위 트랜잭션에 캐시된 미확정 제출이 있어도 다른 트랜잭션의 확정을 보존한다. */
    @Test
    @DisplayName("먼저 조회한 상위 트랜잭션에서도 다른 트랜잭션이 확정한 실행 환경을 덮어쓰지 않는다")
    void preservesRuntimeFixedAfterParentTransactionRead() {
        var saved = storage.register(request("parent-cached", null));
        var changed = new JudgeRuntime("java", 999, "Java (OpenJDK 17.0.9)", "17.0.9", null);
        var parent = new TransactionTemplate(transactionManager);
        try (var executor = Executors.newSingleThreadExecutor()) {
            parent.executeWithoutResult(transaction -> {
                var cached = submissions.findById(saved.submissionId()).orElseThrow();
                assertThat(cached.getRuntime()).isNull();
                var committed = CompletableFuture.supplyAsync(() -> {
                    var runtime = storage.fixRuntime(saved.submissionId(), JAVA);
                    jdbc.update("UPDATE submissions SET status = 'JUDGING' WHERE id = ?", saved.submissionId());
                    return runtime;
                }, executor).orTimeout(20, TimeUnit.SECONDS).join();
                assertThat(committed).isEqualTo(JAVA);
                assertThat(cached.getRuntime()).isNull();
                assertThat(storage.fixRuntime(saved.submissionId(), changed)).isEqualTo(JAVA);
                assertThat(cached.getRuntime().toRuntime()).isEqualTo(JAVA);
            });
        }
        assertThat(runtimes.findById(runtimeId(saved.submissionId())).orElseThrow().toRuntime()).isEqualTo(JAVA);
        assertThat(submissions.findById(saved.submissionId()).orElseThrow().getStatus())
            .isEqualTo(Submission.Status.JUDGING);
        assertThat(runtimes.count()).isEqualTo(1);
    }

    /**
     * 이전 조회 스냅샷 이후 생성된 제출도 최신 행을 읽어 실행 환경을 확정한다.
     *
     * @param fixed 새 제출이 실행 환경까지 확정한 상태인지 여부
     */
    @ParameterizedTest
    @ValueSource(
        booleans = {
            true,
            false
        }
    )
    @DisplayName("상위 트랜잭션의 선조회 이후 생성된 제출도 조회하고 기존 실행 환경을 보존한다")
    void fixesRuntimeForSubmissionCreatedAfterParentRead(boolean fixed) {
        var anchor = storage.register(request("snapshot-anchor", null));
        var changed = new JudgeRuntime("java", 999, "Java (OpenJDK 17.0.9)", "17.0.9", null);
        var expected = fixed ? JAVA : changed;
        var parent = new TransactionTemplate(transactionManager);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var created = parent.execute(transaction -> {
                submissions.findById(anchor.submissionId()).orElseThrow();
                var committed
                    = CompletableFuture
                        .supplyAsync(() -> storage.register(request("snapshot-new", fixed ? JAVA : null)), executor)
                        .orTimeout(20, TimeUnit.SECONDS)
                        .join();
                // 일반 조회에서는 보이지 않는 새 행도 환경 확정 시 현재 읽기로 조회해야 한다.
                assertThat(submissions.findById(committed.submissionId())).isEmpty();
                assertThat(storage.fixRuntime(committed.submissionId(), changed)).isEqualTo(expected);
                return committed;
            });
            assertThat(created).isNotNull();
            assertThat(runtimes.findById(runtimeId(created.submissionId())).orElseThrow().toRuntime())
                .isEqualTo(expected);
        }
        assertThat(runtimes.count()).isEqualTo(1);
    }

    /** 실행 환경 확정도 상위 트랜잭션의 롤백과 함께 되돌린다. */
    @Test
    @DisplayName("상위 트랜잭션에서 확정한 실행 환경은 상위 작업 실패 시 함께 롤백한다")
    void rollsBackRuntimeWithParentTransaction() {
        var saved = storage.register(request("parent-rollback", null));
        var parent = new TransactionTemplate(transactionManager);
        assertThatThrownBy(() -> parent.executeWithoutResult(transaction -> {
            submissions.findById(saved.submissionId()).orElseThrow();
            assertThat(storage.fixRuntime(saved.submissionId(), JAVA)).isEqualTo(JAVA);
            throw new IllegalStateException("상위 작업 실패");
        })).isInstanceOf(IllegalStateException.class).hasMessage("상위 작업 실패");
        assertThat(runtimeId(saved.submissionId())).isNull();
        assertThat(runtimes.count()).isZero();
    }

    /**
     * 서로 다른 제출이 동시에 같은 환경을 선택해도 스냅샷은 한 행만 만든다.
     *
     * @throws Exception 동시 저장이나 결과 조회가 실패한 경우
     */
    @Test
    @DisplayName("서로 다른 제출을 동시에 접수해도 동일 실행 환경 스냅샷은 하나만 생성한다")
    void sharesRuntimeAcrossConcurrentSubmissions() throws Exception {
        var start = new CountDownLatch(1);
        List<Future<StoredSubmission>> pending = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(4)) {
            for (int index = 0; index < 4; index++) {
                String key = "parallel-" + index;
                pending.add(executor.submit(() -> {
                    start.await();
                    return storage.register(request(key, JAVA));
                }));
            }
            start.countDown();
            List<Long> runtimeIds = new ArrayList<>();
            for (var future : pending) {
                runtimeIds.add(runtimeId(future.get(20, TimeUnit.SECONDS).submissionId()));
            }
            assertThat(runtimeIds).doesNotContainNull().containsOnly(runtimeIds.getFirst());
        }
        assertThat(runtimes.count()).isEqualTo(1);
        assertThat(submissions.count()).isEqualTo(4);
        assertThat(jobs.count()).isEqualTo(4);
        assertThat(tests.count()).isEqualTo(8);
    }

    /**
     * 다른 제출 행을 각각 잠근 경우에도 UNIQUE 해시로 환경 공유를 보장한다.
     *
     * @throws Exception 동시 환경 확정이나 결과 조회가 실패한 경우
     */
    @Test
    @DisplayName("서로 다른 미확정 제출이 동시에 같은 실행 환경을 연결해도 한 스냅샷을 공유한다")
    void sharesRuntimeAcrossConcurrentRuntimeSelection() throws Exception {
        var first = storage.register(request("pending-first", null));
        var second = storage.register(request("pending-second", null));
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstRuntime = executor.submit(() -> {
                start.await();
                return storage.fixRuntime(first.submissionId(), JAVA);
            });
            var secondRuntime = executor.submit(() -> {
                start.await();
                return storage.fixRuntime(second.submissionId(), JAVA);
            });
            start.countDown();
            assertThat(firstRuntime.get(20, TimeUnit.SECONDS)).isEqualTo(JAVA);
            assertThat(secondRuntime.get(20, TimeUnit.SECONDS)).isEqualTo(JAVA);
        }
        assertThat(runtimeId(first.submissionId())).isNotNull().isEqualTo(runtimeId(second.submissionId()));
        assertThat(runtimes.count()).isEqualTo(1);
    }

    /** 해시 UNIQUE 위반을 DB가 거부하며 재사용은 기존 생성 시각을 바꾸지 않는다. */
    @Test
    @DisplayName("스냅샷 해시는 DB에서 유일하며 재사용 시 기존 속성과 생성 시각을 보존한다")
    void enforcesUniqueSnapshotHash() {
        var saved = storage.register(request("snapshot-first", JAVA));
        Long id = runtimeId(saved.submissionId());
        var createdAt = NOW.minusDays(1);
        jdbc.update("UPDATE execution_runtimes SET created_at = ? WHERE id = ?", createdAt, id);
        storage.register(request("snapshot-second", JAVA));
        assertThat(runtimes.findById(id).orElseThrow().getCreatedAt()).isEqualTo(createdAt);
        assertThatThrownBy(() -> runtimes.saveAndFlush(ExecutionRuntime.snapshot(JAVA, NOW)))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(runtimes.count()).isEqualTo(1);
    }

    /** 해시만 일치하는 비정상 행을 다른 실행 환경으로 재사용하지 않는다. */
    @Test
    @DisplayName("해시가 같아도 원본 속성이 다르면 충돌로 거절하고 새 제출을 롤백한다")
    void rejectsSnapshotHashCollision() {
        var different = new JudgeRuntime("java", 999, "Java (OpenJDK 17.0.9)", "17.0.9", "-g");
        var saved = storage.register(request("collision-original", different));
        String hash = ExecutionRuntime.snapshot(JAVA, NOW).getSnapshotHash();
        jdbc
            .update(
                "UPDATE execution_runtimes SET snapshot_hash = ? WHERE id = ?",
                hash,
                runtimeId(saved.submissionId())
            );
        assertThatThrownBy(() -> storage.register(request("collision-new", JAVA)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("해시와 원본 속성");
        assertThat(submissions.count()).isEqualTo(1);
        assertThat(jobs.count()).isEqualTo(1);
        assertThat(tests.count()).isEqualTo(2);
        assertThat(runtimes.count()).isEqualTo(1);
    }

    /** 뒤쪽 테스트 저장 실패가 먼저 저장한 원본·작업·실행 환경도 롤백한다. */
    @Test
    @DisplayName("테스트 순번 중복으로 실패하면 제출·작업·결과·새 스냅샷 전체를 롤백한다")
    void rollsBackAllRowsOnDuplicateTestOrder() {
        var original = request("rollback", JAVA);
        var invalid
            = new SubmissionStorageRequest(
                original.submission(),
                original.deadlineAt(),
                List.of(new TestCase(501, 1, true), new TestCase(502, 1, false)),
                JAVA
            );
        assertThatThrownBy(() -> storage.register(invalid)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(submissions.count()).isZero();
        assertThat(jobs.count()).isZero();
        assertThat(tests.count()).isZero();
        assertThat(runtimes.count()).isZero();
    }

    /** 일반 풀이 키는 대소문자를 구분하고 외부 접수는 문맥별로 유일하다. */
    @Test
    @DisplayName("일반 풀이 멱등 키의 binary 유일 제약과 외부 접수 문맥별 유일 제약을 적용한다")
    void enforcesSubmissionIdentity() {
        storage.register(request("Key", null));
        storage.register(request("key", null));
        assertThatThrownBy(() -> storage.register(request("Key", null)))
            .isInstanceOf(DataIntegrityViolationException.class);
        for (var context : List.of(Submission.Context.EXAM, Submission.Context.CONTEST, Submission.Context.DIAGNOSIS)) {
            var draft
                = new SubmissionDraft(
                    11,
                    301,
                    1001,
                    context,
                    401L,
                    901L,
                    null,
                    null,
                    "java",
                    "return 1;",
                    NOW.minusSeconds(30)
                );
            var request = new SubmissionStorageRequest(draft, NOW.plusMinutes(10), TESTS, null);
            storage.register(request);
            assertThatThrownBy(() -> storage.register(request)).isInstanceOf(DataIntegrityViolationException.class);
        }
        assertThat(submissions.count()).isEqualTo(5);
    }

    /** 외부 ID에는 FK를 만들지 않고 내부 계층 FK와 회차 유일 제약은 적용한다. */
    @Test
    @DisplayName("서비스 내부 FK·회차당 작업 유일 제약과 조회 인덱스를 실제 MySQL에서 검증한다")
    void enforcesInternalReferencesAndIndexes() {
        var saved = storage.register(request("references", JAVA));
        var submission = submissions.findById(saved.submissionId()).orElseThrow();
        assertThatThrownBy(() -> jobs.saveAndFlush(JudgeJob.pending(submission, NOW, NOW.plusMinutes(10))))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
            () -> jdbc.update("DELETE FROM execution_runtimes WHERE id = ?", runtimeId(saved.submissionId()))
        ).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(
            () -> jdbc.update("UPDATE judge_jobs SET submission_id = 999999 WHERE id = ?", saved.judgeJobId())
        ).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(
            jdbc
                .queryForList(
                    "SELECT DISTINCT index_name FROM information_schema.statistics "
                        + "WHERE table_schema = DATABASE() "
                        + "AND table_name IN ('submissions', 'judge_jobs', 'judge_test_results')",
                    String.class
                )
        )
            .contains(
                "idx_submissions_member_created_at",
                "idx_submissions_member_problem_created_at",
                "idx_submissions_status_updated_at",
                "idx_submissions_context_member_verdict",
                "idx_judge_jobs_status_next_run_at",
                "idx_judge_jobs_status_lease_until",
                "idx_judge_test_results_token_status"
            );
    }

    /**
     * 모든 종결 사유를 별도의 실행 시도 행으로 보존한다.
     *
     * @param result 시도 종결 사유
     */
    @ParameterizedTest
    @EnumSource(JudgeJobRun.Result.class)
    @DisplayName("종결 실행 이력은 작업과 시도 번호로 유일하며 token 생성 시도와 연결할 수 있다")
    void preservesRunHistory(JudgeJobRun.Result result) {
        var saved = storage.register(request("history", null));
        var job = jobs.findById(saved.judgeJobId()).orElseThrow();
        var error = result == JudgeJobRun.Result.SUCCEEDED ? null : "engine unavailable";
        runs.saveAndFlush(JudgeJobRun.finished(job, 1, "lease-1", result, error, NOW.minusSeconds(1), NOW, NOW));
        runs.saveAndFlush(JudgeJobRun.finished(job, 2, "lease-2", JudgeJobRun.Result.SUCCEEDED, null, NOW, NOW, NOW));
        assertThat(runs.findByJudgeJobIdOrderByTryCount(job.getId()))
            .extracting(JudgeJobRun::getTryCount)
            .containsExactly(1, 2);
        assertThatThrownBy(
            () -> runs.saveAndFlush(JudgeJobRun.finished(job, 1, "duplicate", result, error, NOW, NOW, NOW))
        ).isInstanceOf(DataIntegrityViolationException.class);
        jdbc
            .update(
                "UPDATE judge_test_results SET judge0_token = 'token', token_status = 'CREATED', "
                    + "created_try_count = 2 WHERE judge_job_id = ? AND test_order = 1",
                job.getId()
            );
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM judge_test_results t JOIN judge_job_runs r "
                        + "ON r.judge_job_id = t.judge_job_id AND r.try_count = t.created_try_count "
                        + "WHERE t.judge_job_id = ? AND t.judge0_token = 'token'",
                    Integer.class,
                    job.getId()
                )
        ).isEqualTo(1);
    }

    /** DB 차원에서도 종결 판정과 숨김 반환값 보관 불변식을 지킨다. */
    @Test
    @DisplayName("완료 상태와 판정은 함께 설정해야 하고 숨김 테스트 반환 원문은 저장할 수 없다")
    void rejectsInvalidPersistedState() {
        var saved = storage.register(request("state", null));
        assertThatThrownBy(
            () -> jdbc.update("UPDATE submissions SET status = 'COMPLETED' WHERE id = ?", saved.submissionId())
        ).isInstanceOf(DataAccessException.class).hasMessageContaining("Check constraint");
        assertThatThrownBy(
            () -> jdbc.update("UPDATE submissions SET verdict = 'AC' WHERE id = ?", saved.submissionId())
        ).isInstanceOf(DataAccessException.class).hasMessageContaining("Check constraint");
        assertThatThrownBy(
            () -> jdbc
                .update(
                    "UPDATE judge_test_results SET actual_output = 'secret' "
                        + "WHERE judge_job_id = ? AND is_sample = false",
                    saved.judgeJobId()
                )
        ).isInstanceOf(DataAccessException.class).hasMessageContaining("Check constraint");
    }

    /**
     * 입력 경계에서 UTF-8 코드 크기를 계산한다.
     *
     * @param bytes 코드 바이트 수
     */
    @ParameterizedTest
    @ValueSource(
        ints = {
            65536,
            65537
        }
    )
    @DisplayName("코드 크기는 UTF-8 바이트로 제한하며 경계값을 저장하고 초과는 롤백한다")
    void enforcesCodeSize(int bytes) {
        var original = request("size", null);
        var draft
            = new SubmissionDraft(
                11,
                301,
                1001,
                Submission.Context.PRACTICE,
                null,
                null,
                "size",
                "a".repeat(64),
                "java",
                "x".repeat(bytes),
                NOW
            );
        var request = new SubmissionStorageRequest(draft, original.deadlineAt(), TESTS, null);
        if (bytes == 65536) {
            storage.register(request);
        } else {
            assertThatThrownBy(() -> storage.register(request)).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(submissions.count()).isEqualTo(bytes == 65536 ? 1 : 0);
    }

    /**
     * 테스트용 원본·기한·고정 테스트를 만든다.
     *
     * @param key 제출별 멱등 키
     * @param runtime 고정 실행 환경; 선택 전이면 {@code null}
     * @return 저장 요청
     */
    private SubmissionStorageRequest request(String key, JudgeRuntime runtime) {
        var draft
            = new SubmissionDraft(
                11,
                301,
                1001,
                Submission.Context.PRACTICE,
                null,
                null,
                key,
                "a".repeat(64),
                "java",
                "return \"가\";",
                NOW.minusSeconds(1)
            );
        return new SubmissionStorageRequest(draft, NOW.plusMinutes(10), TESTS, runtime);
    }

    /**
     * 제출의 실행 환경 참조를 DB에서 조회한다.
     *
     * @param submissionId 제출 ID
     * @return 확정한 환경 ID; 미확정이면 {@code null}
     */
    private Long runtimeId(long submissionId) {
        return jdbc.queryForObject("SELECT runtime_id FROM submissions WHERE id = ?", Long.class, submissionId);
    }

    /** 감사 설정의 기본 {@code Clock}보다 우선하는 테스트 시각을 제공한다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class TimeConfiguration {
        /**
         * 나노초 절삭과 서울 시간 변환을 확인할 UTC {@code Clock}을 제공한다.
         *
         * @return 고정 UTC {@code Clock}
         */
        @Bean
        @Primary
        Clock testClock() {
            return Clock.fixed(Instant.parse("2026-10-27T00:00:00.123456789Z"), ZoneOffset.UTC);
        }
    }
}
