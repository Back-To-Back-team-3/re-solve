package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.event.service.OutboxRelay;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.domain.ProblemValidation;
import com.backtoback.problem.problem.dto.request.ProblemRevisionRequest;
import com.backtoback.problem.problem.dto.response.ProblemCreateResponse;
import com.backtoback.problem.problem.dto.response.ProblemRevisionResponse;
import com.backtoback.problem.problem.repository.ProblemCodeAssetRepository;
import com.backtoback.problem.problem.repository.ProblemValidationRepository;

/** 실제 DB에서 새 회차·자동 검증·파일 재사용의 멱등성과 원자성을 검증한다. */
@MockitoBean(types = OutboxRelay.class)
@SpringBootTest(
    properties = {
        "problem.messaging.validation-queue-url=http://localhost:4566/000000000000/problem-validation-queue",
        "spring.flyway.locations=classpath:db/migration,classpath:db/testmigration",
        "spring.cloud.aws.region.static=ap-northeast-2",
        "spring.cloud.aws.credentials.access-key=test",
        "spring.cloud.aws.credentials.secret-key=test",
        "problem.image-storage.bucket=test-bucket"
    }
)
@Testcontainers(disabledWithoutDocker = true)
class ProblemRevisionValidationServiceTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("problem_db");
    @Container
    private static final GenericContainer<?> REDIS
        = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", "test-password");
    @Autowired
    private ProblemCreateService service;
    @MockitoSpyBean
    private ProblemCreateStore store;
    @Autowired
    private ProblemAssetUploadTracking tracking;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private ProblemAssetUploadService uploads;
    @MockitoBean
    private ProblemAssetObjectReader reader;
    @MockitoSpyBean
    private OutboxEventWriter events;
    @Autowired
    private ProblemCodeAssetRepository codes;
    @Autowired
    private ProblemValidationRepository validations;
    @Autowired
    private PlatformTransactionManager transactions;
    private final AtomicReference<CyclicBarrier> race = new AtomicReference<>();

    /**
     * 테스트 컨테이너 연결 정보를 제공한다.
     *
     * @param registry 테스트 속성 등록기
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "test-password");
    }

    /** 외부 S3만 대체하고 파일 추적·완료는 실제 DB에 기록한다. */
    @BeforeEach
    void uploadFixture() {
        race.set(null);
        given(reader.matches(anyString(), anyString(), any(), any())).willReturn(true);
        doAnswer(invocation -> {
            boolean body = invocation.getArgument(3);
            var barrier = race.get();
            if (body && barrier != null) {
                barrier.await(10, TimeUnit.SECONDS);
            }
            var ticket = tracking.start(invocation.getArgument(1), invocation.getArgument(2), body);
            tracking.complete(ticket, "fixed-version");
            return ticket.id();
        }).when(uploads).upload(anyString(), anyLong(), any(), anyBoolean());
    }

    /** 동일 원문의 새 회차가 참조와 사용 회차를 보존하고 새 자동 검증만 만드는지 검증한다. */
    @Test
    @DisplayName("새 회차는 파일을 재사용하고 언어별 최신 통과 코드만 자동 검증한다")
    void preservesFilesAndCurrentRevision() {
        var original = create();
        long problemId = Long.parseLong(original.problemId());
        long revisionId = Long.parseLong(original.problemRevisionId());
        passed(problemId, revisionId, "java", "first");
        long latest = passed(problemId, revisionId, "java", "latest");
        long cpp = passed(problemId, revisionId, "cpp", "cpp");
        jdbc.update("update problems set visibility='PUBLIC' where id=?", problemId);
        clearInvocations(uploads);
        var result = revise(original, "revision-" + UUID.randomUUID(), request(1, "설명"));

        assertThat(result.revisionNumber()).isEqualTo(2);
        assertThat(result.rowVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject("select visibility from problems where id=?", String.class, problemId))
            .isEqualTo("PUBLIC");
        assertThat(validations.currentNumber(problemId)).isEqualTo(1);
        assertThat(
            jdbc
                .queryForObject(
                    "select body_asset_id from problem_revisions where id=?",
                    Long.class,
                    result.problemRevisionId()
                )
        )
            .isEqualTo(
                jdbc.queryForObject("select body_asset_id from problem_revisions where id=?", Long.class, revisionId)
            );
        var automatic
            = jdbc
                .queryForList(
                    "select * from problem_validations where problem_revision_id=?",
                    result.problemRevisionId()
                );
        assertThat(automatic).hasSize(2).allSatisfy(row -> {
            assertThat(row.get("trigger_type")).isEqualTo("AUTO");
            assertThat(row.get("idempotency_key")).isNull();
            assertThat(row.get("result_status")).isNull();
            assertThat(row.get("runtime_key")).isNull();
        });
        assertThat(automatic.stream().map(row -> ((Number)row.get("code_asset_id")).longValue()).toList())
            .containsExactlyInAnyOrder(latest, cpp);
        assertThat(jdbc.queryForObject("""
            select count(*) from outbox_events o join problem_validations v on cast(o.aggregate_id as unsigned)=v.id
            where o.event_type='ProblemValidationRequested' and v.problem_revision_id=?
            """, Integer.class, result.problemRevisionId())).isEqualTo(2);
        assertThat(jdbc.queryForList("""
            select test_asset_id from problem_revision_tests
            where problem_revision_id=? order by test_order
            """, Long.class, result.problemRevisionId())).containsExactlyElementsOf(jdbc.queryForList("""
            select test_asset_id from problem_revision_tests
            where problem_revision_id=? order by test_order
            """, Long.class, revisionId));
        assertThat(jdbc.queryForList("""
            select o.correlation_id from outbox_events o
            join problem_validations v on cast(o.aggregate_id as unsigned)=v.id
            where o.event_type='ProblemValidationRequested' and v.problem_revision_id=?
            """, String.class, result.problemRevisionId())).containsOnly("revision:request_1");
        verifyNoInteractions(uploads);
        jdbc
            .update(
                "update problem_validations set result_status='REJECTED' where problem_revision_id=?",
                result.problemRevisionId()
            );
        assertThat(validations.currentNumber(problemId)).isEqualTo(1);
    }

    /** 과거 버전으로 재요청해도 최초 응답을 우선하고 대상·본문 변경을 충돌로 처리하는지 검증한다. */
    @Test
    @DisplayName("멱등 재요청은 최초 버전을 반환하고 다른 본문과 대상은 거절한다")
    void replayBeforeVersionCheck() {
        var original = create();
        String key = UUID.randomUUID().toString();
        var first = revise(original, key, request(1, "새 설명"));
        revise(original, UUID.randomUUID().toString(), request(2, "더 새 설명"));
        assertThat(revise(original, key, request(1, "새 설명"))).isEqualTo(first);
        assertThatThrownBy(() -> revise(original, key, request(2, "새 설명")))
            .isInstanceOfSatisfying(
                ProblemException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT)
            );
        var other = create();
        assertThatThrownBy(() -> revise(other, key, request(1, "새 설명")))
            .isInstanceOfSatisfying(
                ProblemException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT)
            );
    }

    /**
     * 동일 키와 서로 다른 키의 동시 저장을 실제 업로드 경계에서 경합시킨다.
     *
     * @param sameKey 동일 멱등키 여부
     * @throws Exception 동시 작업 실패
     */
    @ParameterizedTest
    @ValueSource(
        booleans = {
            true,
            false
        }
    )
    @DisplayName("같은 버전의 동시 수정은 한 회차만 저장하고 동일 키에는 같은 응답을 반환한다")
    void concurrentRevision(boolean sameKey) throws Exception {
        var original = create();
        passed(Long.parseLong(original.problemId()), Long.parseLong(original.problemRevisionId()), "java", "code");
        String firstKey = UUID.randomUUID().toString();
        String secondKey = sameKey ? firstKey : UUID.randomUUID().toString();
        race.set(new CyclicBarrier(2));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> attempt(original, firstKey));
            var second = executor.submit(() -> attempt(original, secondKey));
            var results = List.of(first.get(20, TimeUnit.SECONDS), second.get(20, TimeUnit.SECONDS));
            if (sameKey) {
                assertThat(results.getFirst()).isInstanceOf(ProblemRevisionResponse.class).isEqualTo(results.getLast());
            } else {
                assertThat(results.stream().filter(ProblemRevisionResponse.class::isInstance)).hasSize(1);
                assertThat(results.stream().filter(ProblemException.class::isInstance))
                    .singleElement()
                    .satisfies(
                        error -> assertThat(((ProblemException)error).getErrorCode())
                            .isEqualTo(ProblemErrorCode.PROBLEM_VERSION_CONFLICT)
                    );
            }
        }
        assertThat(
            jdbc
                .queryForObject(
                    "select count(*) from problem_revisions where problem_id=?",
                    Integer.class,
                    original.problemId()
                )
        ).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            select count(*) from problem_validations v join problem_revisions r on r.id=v.problem_revision_id
            where r.problem_id=? and v.trigger_type='AUTO'
            """, Integer.class, original.problemId())).isEqualTo(1);
    }

    /** 실제 Outbox INSERT 이후 실패가 회차·참조·요청·버전·감사를 모두 롤백하는지 검증한다. */
    @Test
    @DisplayName("자동 검증 Outbox 저장 후 실패하면 새 회차와 버전 및 모든 참조가 롤백된다")
    void rollsBackAfterOutboxInsert() {
        var original = create();
        passed(Long.parseLong(original.problemId()), Long.parseLong(original.problemRevisionId()), "java", "code");
        int outboxBefore = jdbc.queryForObject("select count(*) from outbox_events", Integer.class);
        doAnswer(invocation -> {
            invocation.callRealMethod();
            assertThat(jdbc.queryForObject("select count(*) from outbox_events", Integer.class))
                .isEqualTo(outboxBefore + 1);
            throw new IllegalStateException("after outbox insert");
        })
            .when(org.springframework.test.util.AopTestUtils.<OutboxEventWriter>getUltimateTargetObject(events))
            .append(anyString(), eq("ProblemValidationRequested"), anyString(), anyString(), anyString(), any());
        assertThatThrownBy(() -> revise(original, UUID.randomUUID().toString(), request(1, "수정 본문")))
            .isInstanceOf(IllegalStateException.class);
        assertThat(
            jdbc
                .queryForObject(
                    "select count(*) from problem_revisions where problem_id=?",
                    Integer.class,
                    original.problemId()
                )
        ).isEqualTo(1);
        assertThat(
            jdbc.queryForObject("select row_version from problems where id=?", Integer.class, original.problemId())
        ).isEqualTo(1);
        assertThat(jdbc.queryForObject("select count(*) from outbox_events", Integer.class)).isEqualTo(outboxBefore);
        assertThat(
            jdbc
                .queryForObject(
                    "select count(*) from audit_logs where target_id=? and action='PROBLEM_REVISION_CREATE'",
                    Integer.class,
                    original.problemId()
                )
        ).isZero();
        assertThat(jdbc.queryForObject("""
            select count(*) from problem_validations v join problem_revisions r on r.id=v.problem_revision_id
            where r.problem_id=? and v.trigger_type='AUTO'
            """, Integer.class, original.problemId())).isZero();
    }

    /** 통과 코드가 없으면 검증을 만들지 않고 수정 권한·상태·버전을 검사하는지 검증한다. */
    @Test
    @DisplayName("통과 코드가 없는 문제도 새 회차를 저장하며 권한과 보관 상태를 검사한다")
    void validatesTargetWithoutPassedCode() {
        var original = create();
        var response = revise(original, UUID.randomUUID().toString(), request(1, "설명"));
        assertThat(
            jdbc
                .queryForObject(
                    "select count(*) from problem_validations where problem_revision_id=?",
                    Integer.class,
                    response.problemRevisionId()
                )
        ).isZero();
        assertThatThrownBy(
            () -> service
                .revise(
                    Long.parseLong(original.problemId()),
                    9002,
                    UUID.randomUUID().toString(),
                    request(2, "설명"),
                    "revision:request_1",
                    "127.0.0.1"
                )
        )
            .isInstanceOfSatisfying(
                ProblemException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(CommonErrorCode.AUTH_ACCESS_DENIED)
            );
        assertThatThrownBy(() -> revise(original, UUID.randomUUID().toString(), request(1, "설명")))
            .isInstanceOfSatisfying(
                ProblemException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ProblemErrorCode.PROBLEM_VERSION_CONFLICT)
            );
        jdbc.update("update problems set visibility='ARCHIVED' where id=?", original.problemId());
        assertThatThrownBy(() -> revise(original, UUID.randomUUID().toString(), request(2, "설명")))
            .isInstanceOfSatisfying(
                ProblemException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ProblemErrorCode.PROBLEM_INVALID_STATE_TRANSITION)
            );
    }

    /**
     * 최초 문제를 생성한다.
     *
     * @return 새 문제 응답
     */
    private ProblemCreateResponse create() {
        return service.create(9001, UUID.randomUUID().toString(), ProblemCreateFixtures.request(), "127.0.0.1");
    }

    /**
     * 전체 수정본을 구성한다.
     *
     * @param version 기대 버전
     * @param description 수정 본문
     * @return 새 회차 입력
     */
    private ProblemRevisionRequest request(int version, String description) {
        var source = ProblemCreateFixtures.request();
        return new ProblemRevisionRequest(
            version,
            source.title(),
            description,
            source.constraints(),
            source.examples(),
            source.difficulty(),
            source.tagIds(),
            source.functionSpec(),
            source.executionLimits(),
            source.testCases()
        );
    }

    /**
     * 원래 작성자로 새 회차를 저장한다.
     *
     * @param original 최초 문제
     * @param key 멱등키
     * @param request 수정본
     * @return 새 회차 응답
     */
    private ProblemRevisionResponse revise(ProblemCreateResponse original, String key, ProblemRevisionRequest request) {
        return service
            .revise(Long.parseLong(original.problemId()), 9001, key, request, "revision:request_1", "127.0.0.1");
    }

    /**
     * 동시 저장의 결과 또는 업무 충돌을 수집한다.
     *
     * @param original 최초 문제
     * @param key 멱등키
     * @return 저장 응답 또는 충돌
     */
    private Object attempt(ProblemCreateResponse original, String key) {
        try {
            return revise(original, key, request(1, "경합 본문"));
        } catch (ProblemException failure) {
            return failure;
        }
    }

    /**
     * 업로드를 완료하고 실제 참조가 있는 통과 코드 요청을 구성한다.
     *
     * @param problemId 문제 ID
     * @param revisionId 회차 ID
     * @param language 언어
     * @param code 코드 내용
     * @return 재사용할 코드 파일 ID
     */
    private long passed(long problemId, long revisionId, String language, String code) {
        var content = ProblemAssetContent.fromBytes(code.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var ticket = tracking.startCode(problemId, 9001L, content);
        tracking.complete(ticket, "fixed-version");
        new TransactionTemplate(transactions).executeWithoutResult(status -> {
            codes.findByIdForUpdate(ticket.id()).orElseThrow().linkToProblem(problemId, 9001L);
            var validation
                = ProblemValidation
                    .request(revisionId, 9001L, language, ticket.id(), UUID.randomUUID().toString(), content.sha256());
            validation.complete("PASSED", "a".repeat(64), "v1", java.time.LocalDateTime.of(2026, 1, 1, 0, 0));
            validations.saveAndFlush(validation);
        });
        return ticket.id();
    }
}
