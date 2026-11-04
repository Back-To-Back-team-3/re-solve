package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.event.client.AwsEventPublisher;
import com.backtoback.common.event.service.OutboxRelay;
import com.backtoback.common.event.store.JdbcOutboxStore;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.config.ProblemMessagingConfiguration;
import com.backtoback.problem.problem.config.ProblemMessagingProperties;
import com.backtoback.problem.problem.dto.request.ProblemValidationRequest;
import com.backtoback.problem.problem.dto.response.ProblemCreateResponse;
import com.backtoback.problem.problem.dto.response.ProblemValidationResponse;

import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageResponse;

/** 실제 DB에서 코드 업로드 추적·원자 접수·멱등 경합·고정 자료 조회를 검증한다. */
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
class ProblemValidationServiceTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("problem_db");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @Autowired
    private ProblemCreateService creates;
    @MockitoSpyBean
    private ProblemValidationStore store;
    @Autowired
    private ProblemValidationService service;
    @Autowired
    private ProblemValidationDataService data;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private OutboxRelay scheduledRelay;
    @Autowired
    private JdbcOutboxStore outbox;
    @Autowired
    private PlatformTransactionManager transactions;
    @Autowired
    private Clock clock;
    @MockitoBean
    private ProblemAssetObjectWriter writer;
    @MockitoBean
    private ProblemAssetObjectReader reader;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final AtomicReference<CyclicBarrier> barrier = new AtomicReference<>();
    private static final ProblemValidationRequest REQUEST = new ProblemValidationRequest("java", "class Solution {}\n");

    /**
     * 실행 중 컨테이너의 연결 주소를 제공한다.
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

    /** S3 경계만 대체하고 원격 호출 중 DB 트랜잭션이 없음을 검사한다. */
    @BeforeEach
    void storage() {
        barrier.set(null);
        given(writer.put(anyString(), anyString(), any())).willAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            String key = call.getArgument(1);
            if (key.startsWith("problem-code/") && barrier.get() != null) {
                barrier.get().await(10, TimeUnit.SECONDS);
            }
            ProblemAssetContent content = call.getArgument(2);
            objects.put(key, content.bytes());
            return "version-1";
        });
        willAnswer(call -> {
            ProblemAssetContent expected = call.getArgument(3);
            assertThat(objects.get(call.getArgument(1))).isEqualTo(expected.bytes());
            return null;
        }).given(reader).verify(anyString(), anyString(), anyString(), any());
        given(reader.read(anyString(), anyString(), anyString())).willAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return objects.get(call.getArgument(1));
        });
    }

    /** 접수·감사·Outbox와 코드 원문·순서·최초 재응답을 함께 확인한다. */
    @Test
    @DisplayName("검증 접수와 고정 자료는 원문·해시·최초 응답을 보존한다")
    void acceptanceAndData() {
        var problem = create(9101);
        String key = UUID.randomUUID().toString();
        var response = accept(problem, 9101, key, REQUEST, null);
        assertThat(response.status()).isEqualTo("REQUESTED");
        long id = Long.parseLong(response.validationId());
        assertThat(count("SELECT COUNT(*) FROM problem_validations WHERE id=?", id)).isEqualTo(1);
        assertThat(
            count("SELECT COUNT(*) FROM audit_logs WHERE action='PROBLEM_VALIDATION_REQUEST' AND actor_id=?", 9101)
        ).isEqualTo(1);
        String payload
            = jdbc
                .queryForObject(
                    "SELECT payload FROM outbox_events WHERE aggregate_id=?",
                    String.class,
                    response.validationId()
                );
        assertThat(payload)
            .contains("ProblemValidationRequested", "request-219")
            .doesNotContain("sourceCode", "objectKey");
        var fixed = data.read(id);
        assertThat(fixed.sourceCode()).isEqualTo(REQUEST.sourceCode());
        assertThat(fixed.testCases()).hasSize(2);
        assertThat(fixed.testCases().get(0).isSample()).isTrue();
        assertThat(fixed.testCases().get(1).isSample()).isFalse();
        assertThat(fixed.testCases().get(0).argumentTexts()).containsEntry("value", "1");
        assertThat(payload).contains(fixed.requestHash());
        jdbc.update("UPDATE problem_validations SET result_status='REJECTED' WHERE id=?", id);
        org.mockito.Mockito.clearInvocations(writer);
        assertThat(accept(problem, 9101, key, REQUEST, null)).isEqualTo(response);
        org.mockito.Mockito.verifyNoInteractions(writer);
        assertError(
            () -> accept(problem, 9101, key, new ProblemValidationRequest("java", "changed"), null),
            CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT
        );
        assertThat(accept(problem, 9101, UUID.randomUUID().toString(), REQUEST, null).validationId())
            .isNotEqualTo(response.validationId());
    }

    /**
     * 동일 회차의 경합을 직렬화하고 패자 파일을 보존·분류한다.
     *
     * @param sameKey 멱등 재요청 여부
     * @throws Exception 실행 대기 실패
     */
    @ParameterizedTest
    @ValueSource(
        booleans = {
            true,
            false
        }
    )
    @DisplayName("동시 접수는 한 요청·Outbox만 저장하고 패자 파일에 실패 분류를 남긴다")
    void concurrentAcceptance(boolean sameKey) throws Exception {
        long owner = sameKey ? 9102 : 9103;
        var problem = create(owner);
        String key = UUID.randomUUID().toString();
        barrier.set(new CyclicBarrier(2));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> outcome(problem, owner, key));
            var second = executor.submit(() -> outcome(problem, owner, sameKey ? key : UUID.randomUUID().toString()));
            Object firstResult = first.get(30, TimeUnit.SECONDS);
            Object secondResult = second.get(30, TimeUnit.SECONDS);
            if (sameKey) {
                assertThat(firstResult).isInstanceOf(ProblemValidationResponse.class).isEqualTo(secondResult);
            } else {
                assertThat(java.util.List.of(firstResult, secondResult))
                    .filteredOn(value -> value instanceof ProblemValidationResponse)
                    .hasSize(1);
                assertThat(java.util.List.of(firstResult, secondResult))
                    .contains(ProblemErrorCode.PROBLEM_VALIDATION_IN_PROGRESS);
            }
        }
        assertThat(count("SELECT COUNT(*) FROM problem_validations WHERE requested_by=?", owner)).isEqualTo(1);
        assertThat(
            count(
                "SELECT COUNT(*) FROM problem_code_assets WHERE owner_id=? AND status='LINKED' "
                    + "AND failure_code IS NULL",
                owner
            )
        ).isEqualTo(1);
        assertThat(
            count(
                "SELECT COUNT(*) FROM problem_code_assets WHERE owner_id=? AND status='PENDING' "
                    + "AND uploaded_at IS NOT NULL AND upload_finished_at IS NOT NULL "
                    + "AND failure_code='VALIDATION_NOT_COMMITTED'",
                owner
            )
        ).isEqualTo(1);
        assertThat(
            count(
                "SELECT COUNT(*) FROM outbox_events o JOIN problem_validations v "
                    + "ON CAST(o.aggregate_id AS UNSIGNED)=v.id WHERE v.requested_by=?",
                owner
            )
        ).isEqualTo(1);
    }

    /** 최종 감사 실패는 파일 연결·요청·Outbox를 함께 롤백한다. */
    @Test
    @DisplayName("최종 감사 실패는 요청·Outbox·파일 연결을 모두 롤백한다")
    void rollback() {
        // 직전 추적 시각을 고정해 실패 분류 자체가 갱신 시각을 변경하는지 확인한다.
        willAnswer(call -> {
            jdbc.update("UPDATE problem_code_assets SET updated_at='2000-01-01' WHERE id=?", (Long)call.getArgument(0));
            return call.callRealMethod();
        }).given(store).recordLinkFailure(org.mockito.ArgumentMatchers.anyLong());
        var problem = create(9104);
        int before = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class);
        assertThatThrownBy(() -> accept(problem, 9104, UUID.randomUUID().toString(), REQUEST, "x".repeat(46)))
            .isInstanceOf(RuntimeException.class);
        assertThat(count("SELECT COUNT(*) FROM problem_validations WHERE requested_by=?", 9104)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(before);
        assertThat(
            count(
                "SELECT COUNT(*) FROM problem_code_assets WHERE owner_id=? AND status='PENDING' "
                    + "AND failure_code='VALIDATION_NOT_COMMITTED' AND updated_at>'2000-01-01'",
                9104
            )
        ).isEqualTo(1);
    }

    /** 작성 권한·진행·통과·보관 상태를 접수 전에 차단한다. */
    @Test
    @DisplayName("권한과 진행·통과·보관 상태를 재검사한다")
    void guards() {
        var problem = create(9105);
        assertError(() -> accept(problem, 9106, "foreign", REQUEST, null), CommonErrorCode.AUTH_ACCESS_DENIED);
        var response = accept(problem, 9105, "initial", REQUEST, null);
        assertError(
            () -> accept(problem, 9105, "next", REQUEST, null),
            ProblemErrorCode.PROBLEM_VALIDATION_IN_PROGRESS
        );
        jdbc.update("UPDATE problem_validations SET result_status='PASSED' WHERE id=?", response.validationId());
        assertError(
            () -> accept(problem, 9105, "next", REQUEST, null),
            ProblemErrorCode.PROBLEM_INVALID_STATE_TRANSITION
        );
        jdbc.update("UPDATE problems SET visibility='ARCHIVED' WHERE id=?", problem.problemId());
        assertError(
            () -> accept(problem, 9105, "python", new ProblemValidationRequest("python3", "pass"), null),
            ProblemErrorCode.PROBLEM_INVALID_STATE_TRANSITION
        );
    }

    /** 고정 파일을 읽지 못하거나 체크섬이 달라지면 최신 자료로 대체하지 않는다. */
    @Test
    @DisplayName("고정 자료 체크섬이 달라지면 503 업무 오류로 처리한다")
    void checksumFailure() {
        var problem = create(9107);
        var response = accept(problem, 9107, "fixed", REQUEST, null);
        String codeKey
            = jdbc.queryForObject("SELECT object_key FROM problem_code_assets WHERE owner_id=?", String.class, 9107);
        objects.put(codeKey, "changed".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertError(
            () -> data.read(Long.parseLong(response.validationId())),
            CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE
        );
    }

    /** 발행 실패 후 같은 저장 메시지와 이벤트 ID로 재시도한다. */
    @Test
    @DisplayName("SQS 발행 실패는 Outbox에 남고 같은 메시지로 재발행한다")
    void publicationRetry() {
        var response = accept(create(9109), 9109, "publish", REQUEST, null);
        String payload
            = jdbc
                .queryForObject(
                    "SELECT payload FROM outbox_events WHERE aggregate_id=?",
                    String.class,
                    response.validationId()
                );
        var sqs = org.mockito.Mockito.mock(SqsClient.class);
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        given(sqs.sendMessage(org.mockito.ArgumentMatchers.any(SendMessageRequest.class))).willAnswer(call -> {
            SendMessageRequest message = call.getArgument(0);
            if (message.messageBody().equals(payload) && attempts.getAndIncrement() == 0) {
                throw new IllegalStateException("발행 일시 실패");
            }
            return SendMessageResponse.builder().messageId("message-id").build();
        });
        String queue = "http://localhost:4566/000000000000/problem-validation-queue";
        var resolver
            = new ProblemMessagingConfiguration()
                .problemEventDestinationResolver(
                    new ProblemMessagingProperties(queue),
                    new com.backtoback.problem.problem.config.ProblemIntegrationProperties(null, null)
                );
        var relay = new OutboxRelay(outbox, new AwsEventPublisher(null, sqs, resolver), List.of(), clock, transactions);
        relay.relayBatch();
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT retry_count FROM outbox_events WHERE aggregate_id=?",
                    Integer.class,
                    response.validationId()
                )
        ).isEqualTo(1);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT status FROM outbox_events WHERE aggregate_id=?",
                    String.class,
                    response.validationId()
                )
        ).isEqualTo("PENDING");
        relay.relayBatch();
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT status FROM outbox_events WHERE aggregate_id=?",
                    String.class,
                    response.validationId()
                )
        ).isEqualTo("PUBLISHED");
        var messages = ArgumentCaptor.forClass(SendMessageRequest.class);
        org.mockito.Mockito.verify(sqs, org.mockito.Mockito.atLeast(2)).sendMessage(messages.capture());
        assertThat(messages.getAllValues())
            .filteredOn(message -> message.messageBody().equals(payload))
            .hasSize(2)
            .allSatisfy(message -> assertThat(message.queueUrl()).isEqualTo(queue));
    }

    /**
     * 최초 멱등 조회가 비어 있던 요청이 사전 검사 전에 커밋된 경쟁 요청을 확인한다.
     *
     * @param sameBody 경쟁 요청과 본문이 같은지 여부
     */
    @ParameterizedTest
    @ValueSource(
        booleans = {
            true,
            false
        }
    )
    @DisplayName("최초 조회 직후 같은 키가 커밋되면 원래 응답 또는 422를 반환한다")
    void commitBeforePreflight(boolean sameBody) {
        long owner = sameBody ? 9110 : 9111;
        var problem = create(owner);
        var first = new AtomicBoolean(true);
        var winner = new AtomicReference<ProblemValidationResponse>();
        willAnswer(call -> {
            if (first.getAndSet(false)) {
                try (var executor = Executors.newSingleThreadExecutor()) {
                    winner
                        .set(
                            executor
                                .submit(() -> accept(problem, owner, "race", REQUEST, null))
                                .get(10, TimeUnit.SECONDS)
                        );
                }
            }
            return call.callRealMethod();
        })
            .given(store)
            .validate(
                Long.parseLong(problem.problemId()),
                Long.parseLong(problem.problemRevisionId()),
                owner,
                REQUEST.language()
            );
        if (sameBody) {
            var replay = accept(problem, owner, "race", REQUEST, null);
            assertThat(replay).isEqualTo(winner.get());
        } else {
            assertError(
                () -> accept(problem, owner, "race", new ProblemValidationRequest("java", "changed"), null),
                CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT
            );
        }
        assertThat(count("SELECT COUNT(*) FROM problem_code_assets WHERE owner_id=?", owner)).isOne();
    }

    /** UTF-8 바이트 제한과 지원 언어를 업로드 전에 검사한다. */
    @Test
    @DisplayName("코드 64KiB 경계는 문자 수가 아닌 UTF-8 바이트로 검사한다")
    void codeSize() {
        var problem = create(9108);
        org.mockito.Mockito.clearInvocations(writer);
        assertError(
            () -> accept(problem, 9108, "large", new ProblemValidationRequest("java", "가".repeat(21846)), null),
            ProblemErrorCode.CODE_TOO_LARGE
        );
        assertError(
            () -> accept(problem, 9108, "unsupported", new ProblemValidationRequest("ruby", "puts 1"), null),
            CommonErrorCode.COMMON_INVALID_REQUEST
        );
        org.mockito.Mockito.verifyNoInteractions(writer);
        var result = accept(problem, 9108, "boundary", new ProblemValidationRequest("java", "a".repeat(65536)), null);
        assertThat(data.read(Long.parseLong(result.validationId())).sourceCode()).hasSize(65536);
    }

    /**
     * 등록된 문제를 만든다.
     *
     * @param owner 관리자
     * @return 최초 회차
     */
    private ProblemCreateResponse create(long owner) {
        return creates.create(owner, UUID.randomUUID().toString(), ProblemCreateFixtures.request(), null);
    }

    /**
     * 고정 입력으로 검증을 접수한다.
     *
     * @param problem 대상
     * @param owner 관리자
     * @param key 멱등키
     * @param request 코드
     * @param ip 감사 주소
     * @return 접수 응답
     */
    private ProblemValidationResponse accept(
        ProblemCreateResponse problem,
        long owner,
        String key,
        ProblemValidationRequest request,
        String ip
    ) {
        return service
            .accept(
                Long.parseLong(problem.problemId()),
                Long.parseLong(problem.problemRevisionId()),
                owner,
                key,
                request,
                "request-219",
                ip
            );
    }

    /**
     * 동시 작업의 성공 또는 업무 오류를 반환한다.
     *
     * @param problem 대상
     * @param owner 관리자
     * @param key 멱등키
     * @return 응답 또는 오류 코드
     */
    private Object outcome(ProblemCreateResponse problem, long owner, String key) {
        try {
            return accept(problem, owner, key, REQUEST, null);
        } catch (ProblemException failure) {
            return failure.getErrorCode();
        }
    }

    /**
     * 조건에 맞는 저장 행 수를 확인한다.
     *
     * @param sql 조회문
     * @param id 대상 ID
     * @return 행 수
     */
    private int count(String sql, long id) {
        return jdbc.queryForObject(sql, Integer.class, id);
    }

    /**
     * 업무 실패 코드를 확인한다.
     *
     * @param action 검증 작업
     * @param code 예상 오류
     */
    private void assertError(Runnable action, com.backtoback.common.exception.ErrorCode code) {
        assertThatThrownBy(action::run)
            .isInstanceOfSatisfying(
                ProblemException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(code)
            );
    }
}
