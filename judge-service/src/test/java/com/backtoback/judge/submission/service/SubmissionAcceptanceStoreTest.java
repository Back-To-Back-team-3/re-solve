package com.backtoback.judge.submission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
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
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.exception.BusinessException;
import com.backtoback.judge.global.config.PersistenceConfiguration;
import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.global.exception.custom.AuthTokenInvalidException;
import com.backtoback.judge.global.exception.custom.CommonDependencyUnavailableException;
import com.backtoback.judge.global.exception.custom.IdempotencyKeyConflictException;
import com.backtoback.judge.global.exception.custom.LevelProblemLockedException;
import com.backtoback.judge.global.exception.custom.MemberSuspendedException;
import com.backtoback.judge.global.exception.custom.ProblemNotAvailableException;
import com.backtoback.judge.global.exception.custom.SubmissionRateLimitedException;
import com.backtoback.judge.global.exception.custom.SubmissionRuntimeUnavailableException;
import com.backtoback.judge.global.exception.custom.UnsupportedLanguageException;
import com.backtoback.judge.judging.domain.ExecutionRuntime;
import com.backtoback.judge.judging.repository.ExecutionRuntimeRepository;
import com.backtoback.judge.judging.service.JudgeLanguageService;
import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;
import com.backtoback.judge.submission.client.SubmissionDependencyClient;
import com.backtoback.judge.submission.dto.MemberStatusResponse;
import com.backtoback.judge.submission.dto.MemberTagLevelsResponse;
import com.backtoback.judge.submission.dto.MemberTagLevelsResponse.TagLevel;
import com.backtoback.judge.submission.dto.ProblemExecutionProfileResponse;
import com.backtoback.judge.submission.dto.ProblemJudgingDataResponse;
import com.backtoback.judge.submission.dto.ProblemValidationResponse;
import com.backtoback.judge.submission.dto.SubmissionCreateRequest;
import com.backtoback.judge.submission.service.dto.SubmissionStorageRequest.TestCase;
import com.backtoback.judge.submission.store.SubmissionRateLimiter;

/** 실제 MySQL에서 접수·만료·경합·롤백을 검증한다. */
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
        SubmissionAcceptanceStore.class,
        SubmissionAcceptanceService.class,
        SubmissionAcceptanceStoreTest.FixedTime.class
    }
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class SubmissionAcceptanceStoreTest {
    private static final LocalDateTime NOW = LocalDateTime.parse("2026-10-31T09:00:00");
    private static final JudgeRuntime JAVA = new JudgeRuntime("java", 91, "Java (OpenJDK 17.0.6)", "17.0.6", null);
    private static final SubmissionCreateRequest REQUEST
        = new SubmissionCreateRequest("301", "1001", "java", "return 1;");
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("judge_db");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    private static LettuceConnectionFactory redisFactory;
    private static StringRedisTemplate realRedis;
    @Autowired
    private Clock clock;
    @Autowired
    private SubmissionAcceptanceService acceptance;
    @Autowired
    private SubmissionAcceptanceStore store;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ExecutionRuntimeRepository runtimes;
    @MockitoBean
    private SubmissionDependencyClient dependencies;
    @MockitoBean
    private JudgeLanguageService languages;
    @MockitoBean
    private SubmissionRateLimiter rate;
    private ProblemValidationResponse validation;

    /**
     * 격리된 MySQL 접속 정보를 등록한다.
     *
     * @param registry 동적 테스트 설정
     */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /** 동시성 회귀 테스트용 실제 Redis에 연결한다. */
    @BeforeAll
    static void connectRedis() {
        redisFactory = new LettuceConnectionFactory(REDIS.getHost(), REDIS.getMappedPort(6379));
        redisFactory.afterPropertiesSet();
        realRedis = new StringRedisTemplate(redisFactory);
        realRedis.afterPropertiesSet();
    }

    /** 테스트 Redis 연결을 종료한다. */
    @AfterAll
    static void closeRedis() {
        if (redisFactory != null) {
            redisFactory.destroy();
        }
    }

    /** DB 원본과 통과 프로필을 준비하며 외부 의존은 명세 응답으로 대체한다. */
    @BeforeEach
    void prepare() {
        try (var connection = redisFactory.getConnection()) {
            connection.serverCommands().flushDb();
        }
        for (String table : List
            .of("judge_test_results", "judge_job_runs", "judge_jobs", "submissions", "execution_runtimes")) {
            jdbc.update("DELETE FROM " + table);
        }
        String key = runtimes.saveAndFlush(ExecutionRuntime.snapshot(JAVA, NOW)).getSnapshotHash();
        var profiles = List.of(new ProblemExecutionProfileResponse("java", key, "future-reference-info"));
        validation
            = new ProblemValidationResponse(
                "301",
                "1001",
                "GENERAL",
                "PUBLIC",
                1,
                List.of("3"),
                2,
                List.of("501", "502"),
                List.of("java"),
                profiles
            );
        given(dependencies.validation(301, 1001)).willReturn(validation);
        given(dependencies.data(301, 1001))
            .willReturn(
                new ProblemJudgingDataResponse(
                    "301",
                    "1001",
                    List.of("java"),
                    profiles,
                    List
                        .of(
                            new ProblemJudgingDataResponse.TestCase("501", 1, true),
                            new ProblemJudgingDataResponse.TestCase("502", 2, false)
                        )
                )
            );
        given(dependencies.status(7)).willReturn(new MemberStatusResponse("7", "ACTIVE"));
        given(dependencies.levels(7)).willReturn(new MemberTagLevelsResponse("7", List.of(new TagLevel("3", "LV1"))));
        given(languages.requireRuntime("java", JAVA)).willReturn(JAVA);
    }

    /** 진행 상태가 바뀌어도 최초 202를 재현하고 외부 검증을 반복하지 않는다. */
    @Test
    @DisplayName("모르는 생성기 참고 정보도 허용하고 재요청은 원래 접수 응답을 반환한다")
    void replaysOriginalResponse() {
        var first = acceptance.create(7, "key", REQUEST);
        jdbc.update("UPDATE submissions SET status='JUDGING' WHERE id=?", Long.parseLong(first.submissionId()));
        given(dependencies.status(7)).willReturn(new MemberStatusResponse("7", "SUSPENDED"));
        assertThat(acceptance.create(7, "key", REQUEST)).isEqualTo(first);
        verify(dependencies, times(1)).status(7);
        verify(rate, times(1)).acquire(7, 301);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_jobs", Long.class)).isEqualTo(1);
        assertThatThrownBy(
            () -> acceptance.create(7, "key", new SubmissionCreateRequest("301", "1001", "java", "return 2;"))
        )
            .isInstanceOfSatisfying(
                IdempotencyKeyConflictException.class,
                exception -> assertThat(exception.getErrorCode().getCode()).isEqualTo("IDEMPOTENCY_KEY_CONFLICT")
            );
    }

    /** 저장 기준으로 확정한 전체 처리 기한은 원래 요청 시각과 재요청에 영향받지 않는다. */
    @Test
    @DisplayName("전체 기한은 접수 저장 기준 10분이며 멱등 재요청으로 연장하지 않는다")
    void preservesProcessingDeadline() {
        var first
            = store
                .accept(
                    7,
                    "key",
                    REQUEST.requestHash(),
                    REQUEST,
                    NOW.minusMinutes(3),
                    JAVA,
                    List.of(new TestCase(501, 1, true), new TestCase(502, 2, false))
                );
        long id = Long.parseLong(first.submissionId());
        assertThat(
            jdbc.queryForObject("SELECT deadline_at FROM judge_jobs WHERE submission_id=?", LocalDateTime.class, id)
        ).isEqualTo(NOW.plusMinutes(10));
        assertThat(
            jdbc.queryForObject("SELECT next_run_at FROM judge_jobs WHERE submission_id=?", LocalDateTime.class, id)
        ).isEqualTo(NOW);
        assertThat(acceptance.create(7, "key", REQUEST)).isEqualTo(first);
        assertThat(
            jdbc.queryForObject("SELECT deadline_at FROM judge_jobs WHERE submission_id=?", LocalDateTime.class, id)
        ).isEqualTo(NOW.plusMinutes(10));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_jobs", Long.class)).isEqualTo(1);
    }

    /** 정확히 24시간 경계에서 키만 해제하고 이전 제출을 보존한다. */
    @Test
    @DisplayName("24시간 만료 키는 새 접수로 재사용하고 원래 코드와 해시는 보존한다")
    void expiresKeyOnly() {
        var first = acceptance.create(7, "key", REQUEST);
        jdbc
            .update(
                "UPDATE submissions SET created_at=? WHERE id=?",
                NOW.minusHours(24),
                Long.parseLong(first.submissionId())
            );
        var second = acceptance.create(7, "key", REQUEST);
        assertThat(second.submissionId()).isNotEqualTo(first.submissionId());
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT idempotency_key FROM submissions WHERE id=?",
                    String.class,
                    Long.parseLong(first.submissionId())
                )
        ).isNull();
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT source_code FROM submissions WHERE id=?",
                    String.class,
                    Long.parseLong(first.submissionId())
                )
        ).isEqualTo(REQUEST.sourceCode());
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT request_hash FROM submissions WHERE id=?",
                    String.class,
                    Long.parseLong(first.submissionId())
                )
        ).isEqualTo(REQUEST.requestHash());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM submissions", Long.class)).isEqualTo(2);
    }

    /**
     * 신규·만료 키 동시 요청의 승자를 한 건만 저장한다.
     *
     * @param expired 이전 만료 키가 있는지 여부
     * @throws Exception 동시 실행 실패
     */
    @ParameterizedTest
    @ValueSource(
        booleans = {
            false,
            true
        }
    )
    @DisplayName("신규 키와 만료 키 모두 동시 동일 요청은 하나의 새 접수만 생성한다")
    void serializesDuplicate(boolean expired) throws Exception {
        if (expired) {
            acceptance.create(7, "key", REQUEST);
            jdbc.update("UPDATE submissions SET created_at=?", NOW.minusHours(24));
        }
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> {
                start.await();
                return acceptance.create(7, "key", REQUEST);
            });
            var second = executor.submit(() -> {
                start.await();
                return acceptance.create(7, "key", REQUEST);
            });
            start.countDown();
            assertThat(first.get(20, TimeUnit.SECONDS)).isEqualTo(second.get(20, TimeUnit.SECONDS));
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM submissions", Long.class)).isEqualTo(expired ? 2L : 1L);
    }

    /** 만료 키 해제 후 테스트 저장 실패도 이전 키까지 롤백한다. */
    @Test
    @DisplayName("새 접수 저장 실패는 만료 키 해제까지 롤백한다")
    void rollsBackExpiredKeyRelease() {
        var first = acceptance.create(7, "key", REQUEST);
        jdbc.update("UPDATE submissions SET created_at=?", NOW.minusHours(24));
        assertThatThrownBy(
            () -> store
                .accept(
                    7,
                    "key",
                    REQUEST.requestHash(),
                    REQUEST,
                    NOW,
                    JAVA,
                    List.of(new TestCase(501, 1, true), new TestCase(502, 1, false))
                )
        ).isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT idempotency_key FROM submissions WHERE id=?",
                    String.class,
                    Long.parseLong(first.submissionId())
                )
        ).isEqualTo("key");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM submissions", Long.class)).isEqualTo(1);
    }

    /** 미진입 태그도 최저 레벨에 포함해 접수를 거절한다. */
    @Test
    @DisplayName("다중 태그 중 하나라도 미진입이면 접수하지 않는다")
    void checksAllTags() {
        given(dependencies.validation(301, 1001))
            .willReturn(
                new ProblemValidationResponse(
                    "301",
                    "1001",
                    "GENERAL",
                    "PUBLIC",
                    1,
                    List.of("3", "4"),
                    2,
                    validation.testAssetIds(),
                    validation.supportedLanguages(),
                    validation.executionProfiles()
                )
            );
        assertThatThrownBy(() -> acceptance.create(7, "key", REQUEST))
            .isInstanceOfSatisfying(
                LevelProblemLockedException.class,
                exception -> assertThat(exception.getErrorCode().getCode()).isEqualTo("LEVEL_PROBLEM_LOCKED")
            );
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM submissions", Long.class)).isZero();
    }

    /**
     * 외부 응답 오류는 DB 저장 전에 거절한다.
     *
     * @param scenario 잘못된 의존성 응답 종류
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "suspended",
            "withdrawn",
            "private",
            "wrong-revision",
            "unknown-runtime",
            "bad-key",
            "duplicate-case",
            "wrong-member",
            "level-unavailable"
        }
    )
    @DisplayName("정지·비공개·계약 불일치·외부 조회 실패는 제출을 저장하지 않는다")
    void rejectsInvalidDependencies(String scenario) {
        String code = "COMMON_DEPENDENCY_UNAVAILABLE";
        switch (scenario) {
            case "withdrawn" -> {
                given(dependencies.status(7)).willReturn(new MemberStatusResponse("7", "WITHDRAWN"));
                code = "AUTH_TOKEN_INVALID";
            }
            case "suspended" -> {
                given(dependencies.status(7)).willReturn(new MemberStatusResponse("7", "SUSPENDED"));
                code = "MEMBER_SUSPENDED";
            }
            case "private" -> {
                given(dependencies.validation(301, 1001))
                    .willReturn(
                        new ProblemValidationResponse(
                            "301",
                            "1001",
                            "GENERAL",
                            "PRIVATE",
                            1,
                            validation.tagIds(),
                            2,
                            validation.testAssetIds(),
                            validation.supportedLanguages(),
                            validation.executionProfiles()
                        )
                    );
                code = "PROBLEM_NOT_AVAILABLE";
            }
            case "wrong-revision" -> given(dependencies.validation(301, 1001))
                .willReturn(
                    new ProblemValidationResponse(
                        "301",
                        "9999",
                        "GENERAL",
                        "PUBLIC",
                        1,
                        validation.tagIds(),
                        2,
                        validation.testAssetIds(),
                        validation.supportedLanguages(),
                        validation.executionProfiles()
                    )
                );
            case "unknown-runtime", "bad-key" -> {
                var profiles
                    = List
                        .of(
                            new ProblemExecutionProfileResponse(
                                "java",
                                scenario.equals("bad-key") ? "bad" : "a".repeat(64),
                                "info"
                            )
                        );
                given(dependencies.validation(301, 1001))
                    .willReturn(
                        new ProblemValidationResponse(
                            "301",
                            "1001",
                            "GENERAL",
                            "PUBLIC",
                            1,
                            validation.tagIds(),
                            2,
                            validation.testAssetIds(),
                            validation.supportedLanguages(),
                            profiles
                        )
                    );
                given(dependencies.data(301, 1001))
                    .willReturn(
                        new ProblemJudgingDataResponse(
                            "301",
                            "1001",
                            List.of("java"),
                            profiles,
                            List
                                .of(
                                    new ProblemJudgingDataResponse.TestCase("501", 1, true),
                                    new ProblemJudgingDataResponse.TestCase("502", 2, false)
                                )
                        )
                    );
                if (!scenario.equals("bad-key")) {
                    code = "SUBMISSION_RUNTIME_UNAVAILABLE";
                }
            }
            case "duplicate-case" -> given(dependencies.data(301, 1001))
                .willReturn(
                    new ProblemJudgingDataResponse(
                        "301",
                        "1001",
                        List.of("java"),
                        validation.executionProfiles(),
                        List
                            .of(
                                new ProblemJudgingDataResponse.TestCase("501", 1, true),
                                new ProblemJudgingDataResponse.TestCase("502", 1, false)
                            )
                    )
                );
            case "wrong-member" ->
                given(dependencies.levels(7)).willReturn(new MemberTagLevelsResponse("999", List.of()));
            case "level-unavailable" ->
                given(dependencies.levels(7)).willThrow(new CommonDependencyUnavailableException());
            default -> throw new IllegalArgumentException("알 수 없는 테스트 사례");
        }
        String expected = code;
        Class<? extends BusinessException> expectedType = switch (scenario) {
            case "withdrawn" -> AuthTokenInvalidException.class;
            case "suspended" -> MemberSuspendedException.class;
            case "private" -> ProblemNotAvailableException.class;
            case "unknown-runtime" -> SubmissionRuntimeUnavailableException.class;
            default -> CommonDependencyUnavailableException.class;
        };
        assertThatThrownBy(() -> acceptance.create(7, "key", REQUEST))
            .isInstanceOfSatisfying(
                expectedType,
                exception -> assertThat(exception.getErrorCode().getCode()).isEqualTo(expected)
            );
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM submissions", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_jobs", Long.class)).isZero();
    }

    /** 기존 언어 검증의 오류도 접수 경계의 전용 예외로 전달한다. */
    @Test
    @DisplayName("지원하지 않는 언어는 전용 예외로 거절하고 저장하거나 요청 한도를 소비하지 않는다")
    void rejectsUnsupportedLanguageWithDedicatedException() {
        var request = new SubmissionCreateRequest("301", "1001", "unknown", "code");
        assertThatThrownBy(() -> acceptance.create(7, "unknown-language", request))
            .isInstanceOf(UnsupportedLanguageException.class);
        verify(rate, never()).acquire(7, 301);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM submissions", Long.class)).isZero();
    }

    /** 기존 런타임 서비스의 고정 환경 미지원 오류를 전용 예외로 전달한다. */
    @Test
    @DisplayName("고정 런타임 가용성 거절은 전용 예외로 전달하고 접수를 저장하지 않는다")
    void rejectsUnavailableRuntimeWithDedicatedException() {
        given(languages.requireRuntime("java", JAVA))
            .willThrow(new JudgeException(JudgeErrorCode.SUBMISSION_RUNTIME_UNAVAILABLE));
        assertThatThrownBy(() -> acceptance.create(7, "unavailable-runtime", REQUEST))
            .isInstanceOf(SubmissionRuntimeUnavailableException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM submissions", Long.class)).isZero();
    }

    /** 만료 직전은 기존 접수 응답을 유지한다. */
    @Test
    @DisplayName("24시간 직전의 동일 키는 새 제출을 생성하지 않는다")
    void preservesKeyBeforeExpiry() {
        var first = acceptance.create(7, "key", REQUEST);
        jdbc.update("UPDATE submissions SET created_at=?", NOW.minusHours(24).plusNanos(1000));
        assertThat(acceptance.create(7, "key", REQUEST)).isEqualTo(first);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM submissions", Long.class)).isEqualTo(1);
    }

    /**
     * 동시 다른 본문은 유일한 승자만 접수하고 나머지는 충돌로 처리한다.
     *
     * @throws Exception 동시 실행 실패
     */
    @Test
    @DisplayName("동시 같은 키의 다른 본문은 한 건만 접수하고 나머지는 422다")
    void rejectsConcurrentDifferentBody() throws Exception {
        var start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> concurrentResult(start, REQUEST));
            var second
                = executor
                    .submit(
                        () -> concurrentResult(start, new SubmissionCreateRequest("301", "1001", "java", "return 2;"))
                    );
            start.countDown();
            assertThat(List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("accepted", "IDEMPOTENCY_KEY_CONFLICT");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM submissions", Long.class)).isEqualTo(1);
    }

    /**
     * 경합 접수 결과를 비교 가능한 값으로 바꾼다.
     *
     * @param start 동시 시작 신호
     * @param request 원본 요청
     * @return 성공 또는 오류 코드
     * @throws InterruptedException 시작 대기 중 인터럽트
     */
    private String concurrentResult(CountDownLatch start, SubmissionCreateRequest request) throws InterruptedException {
        start.await();
        try {
            acceptance.create(7, "key", request);
            return "accepted";
        } catch (BusinessException exception) {
            return exception.getErrorCode().getCode();
        }
    }

    /**
     * 초기 조회 후 생성된 기존 접수를 실제 MySQL·Redis의 결합 경로에서 복원한다.
     *
     * @param differentBody 같은 키의 두 번째 본문이 다른지 여부
     * @throws Exception 동시 요청 실행이나 시작 대기 실패
     */
    @ParameterizedTest
    @ValueSource(
        booleans = {
            false,
            true
        }
    )
    @DisplayName("실제 Redis 제한 중에도 동시 승자 저장 후 같은 본문은 202를 복원하고 다른 본문은 422다")
    void rechecksCommittedRequestAfterRedisLimit(boolean differentBody) throws Exception {
        var service
            = new SubmissionAcceptanceService(
                store,
                dependencies,
                runtimes,
                languages,
                new SubmissionRateLimiter(realRedis),
                clock
            );
        var firstValidation = new CountDownLatch(1);
        var allowFirstSave = new CountDownLatch(1);
        var secondStatus = new CountDownLatch(1);
        var firstCommitted = new CountDownLatch(1);
        var statusCalls = new AtomicInteger();
        given(dependencies.status(7)).willAnswer(invocation -> {
            if (statusCalls.incrementAndGet() == 2) {
                secondStatus.countDown();
                assertThat(firstCommitted.await(10, TimeUnit.SECONDS)).isTrue();
            }
            return new MemberStatusResponse("7", "ACTIVE");
        });
        given(dependencies.validation(301, 1001)).willAnswer(invocation -> {
            firstValidation.countDown();
            assertThat(allowFirstSave.await(10, TimeUnit.SECONDS)).isTrue();
            return validation;
        });
        var secondRequest = differentBody ? new SubmissionCreateRequest("301", "1001", "java", "return 2;") : REQUEST;
        try (var executor = Executors.newFixedThreadPool(2)) {
            try {
                var first = executor.submit(() -> service.create(7, "redis-race", REQUEST));
                assertThat(firstValidation.await(10, TimeUnit.SECONDS)).isTrue();
                var second = executor.submit(() -> {
                    try {
                        return (Object)service.create(7, "redis-race", secondRequest);
                    } catch (BusinessException exception) {
                        return exception.getErrorCode().getCode();
                    }
                });
                // 첫 저장 전에 두 번째 상태 조회까지 진입시켜 두 초기 조회가 모두 빈 결과를 보도록 한다.
                assertThat(secondStatus.await(10, TimeUnit.SECONDS)).isTrue();
                allowFirstSave.countDown();
                var saved = first.get(20, TimeUnit.SECONDS);
                // 실제 Redis 제한이 활성인 상태에서 저장 후 재요청을 진행한다.
                realRedis.opsForValue().set("submission:{7}:problem:301", "1", Duration.ofSeconds(5));
                firstCommitted.countDown();
                assertThat(second.get(20, TimeUnit.SECONDS))
                    .isEqualTo(differentBody ? "IDEMPOTENCY_KEY_CONFLICT" : saved);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM submissions", Long.class)).isEqualTo(1);
                assertThat(realRedis.opsForZSet().zCard("submission:{7}:minute")).isEqualTo(1);
                verify(dependencies, times(1)).validation(301, 1001);
            } finally {
                allowFirstSave.countDown();
                firstCommitted.countDown();
            }
        }
    }

    /** 기존 접수가 없는 새 키는 Redis 제한을 그대로 적용한다. */
    @Test
    @DisplayName("기존 멱등 접수가 없으면 실제 Redis의 429와 Retry-After를 유지한다")
    void preservesRedisLimitForNewRequest() {
        var service
            = new SubmissionAcceptanceService(
                store,
                dependencies,
                runtimes,
                languages,
                new SubmissionRateLimiter(realRedis),
                clock
            );
        realRedis.opsForValue().set("submission:{7}:problem:301", "1", Duration.ofSeconds(5));
        assertThatThrownBy(() -> service.create(7, "new-key", REQUEST))
            .isInstanceOfSatisfying(SubmissionRateLimitedException.class, exception -> {
                assertThat(exception.getErrorCode().getCode()).isEqualTo("SUBMISSION_RATE_LIMITED");
                assertThat(Long.parseLong(exception.getResponseHeaders().getFirst("Retry-After"))).isBetween(1L, 5L);
            });
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM submissions", Long.class)).isZero();
    }

    /** 테스트 시각을 고정한다. */
    @TestConfiguration(proxyBeanMethods = false)
    static class FixedTime {
        /**
         * 감사·접수 시각을 동일하게 고정한다.
         *
         * @return 고정 {@code Clock}
         */
        @Bean
        @Primary
        Clock fixedTestClock() {
            return Clock.fixed(Instant.parse("2026-10-31T00:00:00Z"), ZoneOffset.UTC);
        }
    }
}
