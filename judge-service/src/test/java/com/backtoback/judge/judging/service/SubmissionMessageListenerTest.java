package com.backtoback.judge.judging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.judge.judging.domain.ExecutionRuntime;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.judging.dto.FunctionSpec;
import com.backtoback.judge.judging.dto.ProblemValidationDataResponse;
import com.backtoback.judge.judging.dto.SubmissionJudgingDataResponse;
import com.backtoback.judge.judging.dto.TypeSpec;
import com.backtoback.judge.judging.repository.ExecutionRuntimeRepository;
import com.backtoback.judge.judging.service.execution.dto.JudgeExecutionResultResponse.State;
import com.backtoback.judge.judging.service.execution.dto.JudgeRuntime;
import com.backtoback.judge.judging.service.execution.dto.JudgeTestResult;
import com.backtoback.judge.judging.store.ExternalSubmissionAcceptanceStore;
import com.backtoback.judge.judging.store.SubmissionExecutionStore;
import com.backtoback.judge.submission.domain.Submission.Context;
import com.backtoback.judge.submission.domain.SubmissionDraft;
import com.backtoback.judge.submission.dto.ProblemExecutionProfileResponse;

import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import tools.jackson.databind.ObjectMapper;

/** 실제 SQS 수신부터 업무 저장·메시지 삭제·SNS 결과 발행까지 서비스 구성을 검증한다. */
@SpringBootTest(
    properties = {
        "judge.messaging.enabled=true",
        "spring.data.redis.password=",
        "REDIS_PASSWORD="
    }
)
@ActiveProfiles("local")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class SubmissionMessageListenerTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    @Container
    static final LocalStackContainer LOCALSTACK
        = new LocalStackContainer("localstack/localstack:3.8").withServices("sns", "sqs");
    private static String inputQueue;
    private static String outputQueue;
    private static String validationDeadLetterQueue;
    private static String submissionQueue;
    private static String deadLetterQueue;
    private static String deadLetterArn;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager manager;
    @Autowired
    private ObjectMapper mapper;

    // 수신·발행 검증 중 작업 자체를 소비하지 않도록 상위 실행만 대체한다.
    @MockitoBean
    private ValidationWorker worker;
    @MockitoBean
    private SubmissionWorker submissionWorker;
    @Autowired
    private SubmissionExecutionStore execution;
    @Autowired
    private ExecutionRuntimeRepository runtimes;
    @Autowired
    private SubmissionMessageListener submissions;
    @Autowired
    private ExternalSubmissionAcceptanceStore acceptance;

    /**
     * 리스너 시작 전에 Standard 큐·토픽·결과 구독을 준비한다.
     *
     * @param registry 서비스 인프라 설정
     */
    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("LOCALSTACK_ENDPOINT", LOCALSTACK::getEndpoint);
        registry.add("spring.cloud.aws.region.static", LOCALSTACK::getRegion);
        try (
            var sqs = sqsClient();
            var sns
                = SnsClient
                    .builder()
                    .endpointOverride(LOCALSTACK.getEndpoint())
                    .region(Region.of(LOCALSTACK.getRegion()))
                    .credentialsProvider(credentials())
                    .build()
        ) {
            validationDeadLetterQueue
                = sqs.createQueue(request -> request.queueName("problem-validation-queue-dlq")).queueUrl();
            inputQueue = createSourceQueue(sqs, "problem-validation-queue", queueArn(sqs, validationDeadLetterQueue));
            deadLetterQueue = sqs.createQueue(request -> request.queueName("exam-submission-queue-dlq")).queueUrl();
            deadLetterArn = queueArn(sqs, deadLetterQueue);
            submissionQueue = createSourceQueue(sqs, "exam-submission-queue", deadLetterArn);
            outputQueue = sqs.createQueue(request -> request.queueName("contest-queue")).queueUrl();
            var topic = sns.createTopic(request -> request.name("judge-events")).topicArn();
            var queueArn = queueArn(sqs, outputQueue);
            var policy = """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"Service":"sns.amazonaws.com"},
                 "Action":"sqs:SendMessage","Resource":"%s","Condition":{"ArnEquals":{"aws:SourceArn":"%s"}}}]}
                """.formatted(queueArn, topic);
            sqs
                .setQueueAttributes(
                    request -> request.queueUrl(outputQueue).attributes(Map.of(QueueAttributeName.POLICY, policy))
                );
            sns
                .subscribe(
                    request -> request
                        .topicArn(topic)
                        .protocol("sqs")
                        .endpoint(queueArn)
                        .attributes(Map.of("RawMessageDelivery", "true", "FilterPolicy", """
                            {"eventType":["ExamSubmissionJudged"],"context":["EXAM"]}
                            """))
                );
            registry.add("judge.messaging.topic-arn", () -> topic);
            registry.add("judge.messaging.validation-queue-url", () -> inputQueue);
            registry.add("judge.messaging.submission-queue-url", () -> submissionQueue);
        }
    }

    /**
     * 테스트에서 재시도·격리를 빠르게 확인할 수 있는 큐를 생성한다.
     *
     * @param sqs 테스트 클라이언트
     * @param queueName 요청 큐 이름
     * @param dlqArn 격리 큐 ARN
     * @return 요청 큐 URL
     */
    private static String createSourceQueue(SqsClient sqs, String queueName, String dlqArn) {
        return sqs
            .createQueue(
                request -> request
                    .queueName(queueName)
                    .attributes(
                        Map.of(QueueAttributeName.VISIBILITY_TIMEOUT, "1", QueueAttributeName.REDRIVE_POLICY, """
                            {"deadLetterTargetArn":"%s","maxReceiveCount":"3"}
                            """.formatted(dlqArn))
                    )
            )
            .queueUrl();
    }

    /**
     * LocalStack 큐의 ARN을 조회한다.
     *
     * @param sqs 테스트 클라이언트
     * @param queueUrl 조회할 큐
     * @return 큐 ARN
     */
    private static String queueArn(SqsClient sqs, String queueUrl) {
        return sqs
            .getQueueAttributes(request -> request.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN))
            .attributes()
            .get(QueueAttributeName.QUEUE_ARN);
    }

    /**
     * 업무 ACK가 없어서 격리된 원문을 확인하고 다음 시나리오와 섞이지 않게 지운다.
     *
     * @param sqs 테스트 클라이언트
     * @param queueUrl 확인할 DLQ
     * @param expected 격리될 원문
     */
    private void assertDeadLetter(SqsClient sqs, String queueUrl, String expected) {
        await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
            var messages = sqs.receiveMessage(request -> request.queueUrl(queueUrl).waitTimeSeconds(1)).messages();
            assertThat(messages).hasSize(1);
            assertThat(messages.getFirst().body()).isEqualTo(expected);
            sqs.deleteMessage(request -> request.queueUrl(queueUrl).receiptHandle(messages.getFirst().receiptHandle()));
        });
    }

    /** 각 시나리오가 독립적인 최초 접수에서 시작하도록 업무 데이터를 비운다. */
    @BeforeEach
    void clearRows() {
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM judge_test_results");
        jdbc.update("DELETE FROM judge_job_runs");
        jdbc.update("DELETE FROM judge_jobs");
        jdbc.update("DELETE FROM submissions");
        jdbc.update("DELETE FROM processed_events");
    }

    /** 두 큐에서 알려지지 않은 이벤트가 공통 소비 규약을 따르는지 확인한다. */
    @Test
    @DisplayName("모르는 이벤트는 두 큐에서 소비 기록만 커밋한 뒤 삭제한다")
    void acknowledgesUnknownEventsWithoutDispatching() {
        try (var sqs = sqsClient()) {
            sqs
                .sendMessage(
                    request -> request
                        .queueUrl(inputQueue)
                        .messageBody(examMessage(UUID.randomUUID(), "FutureJudgeEvent"))
                );
            sqs
                .sendMessage(
                    request -> request
                        .queueUrl(submissionQueue)
                        .messageBody(examMessage(UUID.randomUUID(), "FutureJudgeEvent"))
                );
            await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
                assertThat(count("processed_events")).isEqualTo(2);
                for (String queue : List.of(inputQueue, submissionQueue)) {
                    assertThat(
                        sqs
                            .getQueueAttributes(
                                request -> request
                                    .queueUrl(queue)
                                    .attributeNames(
                                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE
                                    )
                            )
                            .attributes()
                            .values()
                    ).containsExactlyInAnyOrder("0", "0");
                }
            });
        }
        assertThat(count("judge_jobs")).isZero();
        assertThat(count("judge_validation_jobs")).isZero();
    }

    /** 두 큐의 글로벌 처리기 공유가 큐 경계를 우회하지 않는지 확인한다. */
    @Test
    @DisplayName("검증과 시험 메시지가 서로 바뀐 큐에 들어오면 업무에 반영하거나 ACK하지 않는다")
    void rejectsCrossDelivery() {
        String exam = examMessage(UUID.randomUUID(), "ExamSubmissionRequested");
        String validation = message();
        try (var sqs = sqsClient()) {
            sqs.sendMessage(request -> request.queueUrl(inputQueue).messageBody(exam));
            sqs.sendMessage(request -> request.queueUrl(submissionQueue).messageBody(validation));
            assertDeadLetter(sqs, validationDeadLetterQueue, exam);
            assertDeadLetter(sqs, deadLetterQueue, validation);
        }
        assertThat(count("submissions")).isZero();
        assertThat(count("judge_validation_jobs")).isZero();
        assertThat(count("processed_events")).isZero();
    }

    /**
     * 서로 다른 이벤트가 같은 외부 접수 유일 키에서 실제로 잠금 경합하는지 확인한다.
     *
     * @throws Exception 작업 완료를 기다리는 중 실패한 경우
     */
    @Test
    @DisplayName("같은 접수의 두 번째 이벤트는 MySQL 유일 키 잠금 뒤 최초 제출과 작업을 유지한다")
    void serializesConcurrentReceipts() throws Exception {
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var acknowledgement = mock(Acknowledgement.class);
        var first
            = new SubmissionDraft(
                9,
                101,
                1001,
                Context.EXAM,
                55L,
                71L,
                null,
                null,
                "java",
                "first",
                LocalDateTime.parse("2026-10-31T09:00:00.123456")
            );
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var original = executor.submit(() -> new TransactionTemplate(manager).executeWithoutResult(status -> {
                acceptance.accept(first);
                locked.countDown();
                try {
                    assertThat(release.await(15, TimeUnit.SECONDS)).isTrue();
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                var duplicate
                    = executor
                        .submit(
                            () -> submissions
                                .receive(examMessage(UUID.randomUUID(), "ExamSubmissionRequested"), acknowledgement)
                        );
                var monitor
                    = new JdbcTemplate(new DriverManagerDataSource(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword()));
                await().atMost(Duration.ofSeconds(5)).untilAsserted(() -> assertThat(monitor.queryForObject("""
                    SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                    JOIN performance_schema.data_locks l ON l.ENGINE_LOCK_ID=w.REQUESTING_ENGINE_LOCK_ID
                    WHERE l.OBJECT_SCHEMA=? AND l.OBJECT_NAME='submissions'
                    AND l.INDEX_NAME='uk_submissions_context_source_submission'
                    """, Integer.class, MYSQL.getDatabaseName())).isPositive());
                verifyNoInteractions(acknowledgement);
                assertThat(count("submissions")).isZero();
                release.countDown();
                original.get(10, TimeUnit.SECONDS);
                duplicate.get(10, TimeUnit.SECONDS);
                verify(acknowledgement).acknowledge();
                assertThat(count("submissions")).isEqualTo(1);
                assertThat(count("judge_jobs")).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT source_code FROM submissions", String.class)).isEqualTo("first");
            } finally {
                release.countDown();
            }
        }
    }

    /** 저장 실패 이후에는 동일한 이벤트를 정상적으로 다시 처리할 수 있는지 확인한다. */
    @Test
    @DisplayName("작업 INSERT 실패는 제출과 소비 기록까지 롤백하고 메시지를 삭제하지 않는다")
    void rollsBackFailedJobInsert() {
        var event = examMessage(UUID.randomUUID(), "ExamSubmissionRequested");
        var acknowledgement = mock(Acknowledgement.class);
        rejectJobInsert();
        try {
            assertThatThrownBy(() -> submissions.receive(event, acknowledgement))
                .isInstanceOf(DataAccessException.class);
            verifyNoInteractions(acknowledgement);
            assertThat(count("processed_events")).isZero();
            assertThat(count("submissions")).isZero();
            assertThat(count("judge_jobs")).isZero();
        } finally {
            jdbc.execute("ALTER TABLE judge_jobs DROP CHECK chk_test_reject_first_attempt");
        }
        submissions.receive(event, acknowledgement);
        verify(acknowledgement).acknowledge();
        assertThat(count("judge_jobs")).isEqualTo(1);
    }

    /** DLQ 이동 후 실제 SQS 재전달과 운영자 감사 저장을 검증한다. */
    @Test
    @DisplayName("시험 DLQ 재전달과 UUID 표기가 다른 감사 재시도에도 최초 접수와 감사 행을 보존한다")
    void redrivesAndAudits() {
        jdbc.update("DELETE FROM audit_logs");
        // given: DB 작업 INSERT가 실패해 SQS가 원본 메시지를 격리한다.
        var eventId = UUID.randomUUID();
        String event = examMessage(eventId, "ExamSubmissionRequested");
        try (var sqs = sqsClient()) {
            rejectJobInsert();
            try {
                sqs.sendMessage(request -> request.queueUrl(submissionQueue).messageBody(event));
                await()
                    .atMost(Duration.ofSeconds(25))
                    .untilAsserted(
                        () -> assertThat(
                            sqs
                                .getQueueAttributes(
                                    request -> request
                                        .queueUrl(deadLetterQueue)
                                        .attributeNames(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                                )
                                .attributes()
                                .get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)
                        ).isEqualTo("1")
                    );
                assertThat(count("processed_events")).isZero();
                assertThat(count("submissions")).isZero();
            } finally {
                jdbc.execute("ALTER TABLE judge_jobs DROP CHECK chk_test_reject_first_attempt");
            }

            // when: 원인이 해소된 뒤 DLQ 이동 작업으로 원래 큐에 재전달한다.
            String taskHandle = sqs.startMessageMoveTask(request -> request.sourceArn(deadLetterArn)).taskHandle();
            assertThat(taskHandle).isNotBlank();
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(sqs.listMessageMoveTasks(request -> request.sourceArn(deadLetterArn)).results())
                    .singleElement()
                    .satisfies(task -> {
                        assertThat(task.status()).isEqualTo("COMPLETED");
                        assertThat(task.approximateNumberOfMessagesMoved()).isEqualTo(1);
                    });
                assertThat(count("judge_jobs")).isEqualTo(1);
            });
            auditRedrive(eventId.toString(), taskHandle, 25L);
            auditRedrive(eventId.toString(), taskHandle, 26L);
            auditRedrive(eventId.toString().replace("-", ""), " " + taskHandle + " ", 27L);
            sqs.sendMessage(request -> request.queueUrl(submissionQueue).messageBody(event));

            // then: 이동 완료 후 같은 이벤트가 다시 배달돼도 최초 제출과 작업이 유지된다.
            await()
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(
                    () -> assertThat(
                        sqs
                            .getQueueAttributes(
                                request -> request
                                    .queueUrl(submissionQueue)
                                    .attributeNames(
                                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                        QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE
                                    )
                            )
                            .attributes()
                            .values()
                    ).containsExactlyInAnyOrder("0", "0")
                );
            assertThat(count("submissions")).isEqualTo(1);
            assertThat(count("judge_jobs")).isEqualTo(1);
            assertThat(count("processed_events")).isEqualTo(1);
            assertThat(count("audit_logs")).isEqualTo(1);
            var audit = jdbc.queryForMap("SELECT actor_id,action,target_id,reason,after_state FROM audit_logs");
            assertThat(audit)
                .containsEntry("actor_id", 25L)
                .containsEntry("target_id", eventId.toString())
                .containsEntry("action", "DLQ_REDRIVE")
                .containsEntry("reason", "DB 장애 복구");
            assertThat(mapper.readTree((String)audit.get("after_state")).get("moveTaskHandle").asString())
                .isEqualTo(taskHandle);
        }
    }

    /** 실제 업무 트랜잭션의 작업 INSERT만 실패하도록 테스트 DB에 제약을 주입한다. */
    private void rejectJobInsert() {
        jdbc.execute("""
            ALTER TABLE judge_jobs ADD CONSTRAINT chk_test_reject_first_attempt CHECK (try_count <> 1)
            """);
    }

    /**
     * 운영에서 사용하는 SQL로 테스트 재전달의 감사 행을 기록한다.
     *
     * @param eventId 운영자가 입력한 원본 이벤트 ID 문자열
     * @param taskHandle LocalStack의 이동 작업 핸들
     * @param actorId 감사 기록 집행자
     */
    private void auditRedrive(String eventId, String taskHandle, long actorId) {
        jdbc.execute((ConnectionCallback<Void>)connection -> {
            try (var statement = connection.prepareStatement("""
                SET @redrive_actor_id=?, @redrive_event_id=?, @redrive_receipt_id=?,
                    @redrive_task_handle=?, @redrive_reason=?
                """)) {
                statement.setLong(1, actorId);
                statement.setString(2, eventId);
                statement.setLong(3, 71L);
                statement.setString(4, taskHandle);
                statement.setString(5, "DB 장애 복구");
                statement.execute();
            }
            ScriptUtils
                .executeSqlScript(connection, new ClassPathResource("operations/audit-exam-submission-redrive.sql"));
            return null;
        });
    }

    /**
     * 검증 대상 테이블의 행 수를 조회한다.
     *
     * @param table 테스트가 정한 테이블명
     * @return 해당 테이블의 행 수
     */
    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    /** 커밋 이후 삭제 실패가 최초 접수 원본이나 채점 작업을 바꾸지 않는지 확인한다. */
    @Test
    @DisplayName("ACK 실패 뒤 재전달해도 최초 코드와 작업을 그대로 유지한다")
    void preservesFirstReceiptAfterAckFailure() {
        submissions.receive(examMessage(UUID.randomUUID(), "ExamSubmissionRequested"), mock(Acknowledgement.class));
        UUID replayId = UUID.randomUUID();
        String replay = examMessage(replayId, "ExamSubmissionRequested").replace("code\"", "changed\"");

        var failedAck = mock(Acknowledgement.class);
        willThrow(new IllegalStateException("ACK 실패")).given(failedAck).acknowledge();
        assertThatThrownBy(() -> submissions.receive(replay, failedAck)).isInstanceOf(IllegalStateException.class);

        var retriedAck = mock(Acknowledgement.class);
        submissions.receive(replay, retriedAck);
        verify(retriedAck).acknowledge();
        assertThat(count("judge_jobs")).isEqualTo(1);
        assertThat(count("processed_events")).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT source_code FROM submissions WHERE context='EXAM'", String.class))
            .isEqualTo("code");
    }

    /** 중복 접수를 하나로 묶고 태그가 없는 시험 문제도 자료 검증을 거쳐 J2를 발행하는지 확인한다. */
    @Test
    @DisplayName("태그 없는 시험 문제의 중복 접수를 흡수하고 고정 런타임으로 확정한 J2를 SNS로 전달한다")
    void receivesExamAndPublishesResult() {
        // given: 같은 접수를 여러 이벤트로 전달한다.
        try (var sqs = sqsClient()) {
            String first = examMessage(UUID.randomUUID(), "ExamSubmissionRequested");
            sqs.sendMessage(request -> request.queueUrl(submissionQueue).messageBody(first));
            sqs.sendMessage(request -> request.queueUrl(submissionQueue).messageBody(first));
            sqs
                .sendMessage(
                    request -> request
                        .queueUrl(submissionQueue)
                        .messageBody(examMessage(UUID.randomUUID(), "ExamSubmissionRequested"))
                );
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(
                    jdbc
                        .queryForObject(
                            "SELECT COUNT(*) FROM processed_events WHERE event_type='ExamSubmissionRequested'",
                            Integer.class
                        )
                ).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_jobs", Integer.class)).isEqualTo(1);
                var counts
                    = sqs
                        .getQueueAttributes(
                            request -> request
                                .queueUrl(submissionQueue)
                                .attributeNames(
                                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE
                                )
                        )
                        .attributes();
                assertThat(counts.values()).containsExactlyInAnyOrder("0", "0");
            });
            // when: 고정 회차의 런타임으로 시험 작업을 종결한다.
            assertThat(jdbc.queryForObject("SELECT unknown_retry_count FROM judge_jobs", Integer.class)).isZero();
            var lease = execution.claim().orElseThrow();
            var input = execution.input(lease);
            assertThat(input.revisionId()).isEqualTo(1001L);
            assertThat(input.runtime()).isNull();

            var runtime = new JudgeRuntime("java", 62, "Java (OpenJDK 17)", "17", null);
            var snapshot = ExecutionRuntime.snapshot(runtime, LocalDateTime.of(2026, 10, 31, 9, 0));
            new TransactionTemplate(manager).executeWithoutResult(status -> runtimes.insertIfAbsent(snapshot));
            var data
                = new SubmissionJudgingDataResponse(
                    "101",
                    "1001",
                    "시험 문제",
                    1,
                    List.of(),
                    List.of(),
                    new FunctionSpec("solution", List.of(), new TypeSpec(TypeSpec.Kind.INT32, null, null, null, null)),
                    new ProblemValidationDataResponse.Limits(1000, 6000, 1024, 64),
                    List
                        .of(
                            new SubmissionJudgingDataResponse.TestCase(
                                "42",
                                1,
                                true,
                                new SubmissionJudgingDataResponse.Asset("test", "v1", "a".repeat(64))
                            )
                        ),
                    List.of(new ProblemExecutionProfileResponse("java", snapshot.getSnapshotHash(), "java-runner-v1"))
                );
            data.validate(input.problemId(), input.revisionId());
            assertThat(execution.initializeExternal(lease, data, input.language())).contains(runtime);
            execution.validateCases(lease, data);
            assertThat(execution.input(lease).runtime()).isEqualTo(runtime);
            assertThat(execution.prepare(lease)).isTrue();
            assertThat(execution.beginCreate(lease, 1)).isTrue();
            assertThat(execution.saveToken(lease, 1, "token")).isTrue();
            assertThat(
                execution
                    .saveResult(
                        lease,
                        1,
                        "token",
                        new JudgeTestResult(
                            State.COMPLETED,
                            JudgeVerdict.CE,
                            1,
                            java.math.BigDecimal.ONE,
                            null,
                            null,
                            "compile error"
                        )
                    )
            ).isTrue();
            assertThat(execution.complete(lease, data)).isTrue();
            assertThat(execution.complete(lease, data)).isFalse();
            // then: 문맥 필터를 통과한 J2의 계약 필드를 확인한다.
            var messages
                = sqs
                    .receiveMessage(
                        request -> request.queueUrl(outputQueue).waitTimeSeconds(10).messageAttributeNames("All")
                    )
                    .messages();
            assertThat(messages).hasSize(1);
            var result = mapper.readTree(messages.getFirst().body());
            assertThat(result.get("eventType").asString()).isEqualTo("ExamSubmissionJudged");
            assertThat(messages.getFirst().messageAttributes().get("context").stringValue()).isEqualTo("EXAM");
            var body = (tools.jackson.databind.node.ObjectNode)result;
            for (String header : List
                .of("eventId", "eventType", "schemaVersion", "occurredAt", "aggregateId", "correlationId")) {
                assertThat(body.remove(header)).isNotNull();
            }
            assertThat(body).isEqualTo(mapper.readTree("""
                {
                  "context": "EXAM",
                  "examId": "55",
                  "examSubmissionId": "71",
                  "submissionId": "%d",
                  "userId": "9",
                  "problemId": "101",
                  "problemRevisionId": "1001",
                  "judgeAttempt": 1,
                  "status": "COMPLETED",
                  "verdict": "CE",
                  "passedCount": 0,
                  "totalCount": 1,
                  "maxTimeMs": null,
                  "maxMemoryKb": null,
                  "submittedAt": "2026-10-31T09:00:00.123456+09:00"
                }
                """.formatted(lease.submissionId())));
            sqs
                .deleteMessage(
                    request -> request.queueUrl(outputQueue).receiptHandle(messages.getFirst().receiptHandle())
                );
        }
    }

    /** 미지원 문맥과 잘못된 시험 이벤트는 소비 기록·ACK를 남기지 않는다. */
    @Test
    @DisplayName("미등록 진단 요청과 잘못된 시험 요청은 ACK하지 않고 소비 기록을 남기지 않는다")
    void rejectsUnprocessedRequests() {
        UUID unsupported = UUID.randomUUID();
        UUID invalid = UUID.randomUUID();
        var acknowledgement = mock(Acknowledgement.class);
        assertThatThrownBy(
            () -> submissions.receive(examMessage(unsupported, "DiagnosisSubmissionRequested"), acknowledgement)
        ).isInstanceOf(IllegalArgumentException.class);
        String wrongReceipt
            = examMessage(invalid, "ExamSubmissionRequested")
                .replace("\"aggregateId\":\"71\"", "\"aggregateId\":\"72\"");
        assertThatThrownBy(() -> submissions.receive(wrongReceipt, acknowledgement))
            .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(acknowledgement);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM processed_events WHERE event_id IN (?,?)",
                    Integer.class,
                    unsupported.toString(),
                    invalid.toString()
                )
        ).isZero();
    }

    /**
     * 같은 시험 접수를 서로 다른 이벤트 ID로 보낼 수 있는 메시지를 만든다.
     *
     * @param eventId 전달 식별자
     * @param type 채점 요청 이벤트명
     * @return SQS 원문
     */
    private String examMessage(UUID eventId, String type) {
        return """
            {"eventId":"%s","eventType":"%s","schemaVersion":"v1",
             "occurredAt":"2026-10-31T09:00:00.123456+09:00","aggregateId":"71","correlationId":"exam-71",
             "examId":"55","examSubmissionId":"71","participantId":"7","userId":"9",
             "problemId":"101","problemRevisionId":"1001","language":"java","sourceCode":"code",
             "draftSeq":"1","submissionType":"MANUAL","receivedAt":"2026-10-31T09:00:00.123456+09:00"}
            """.formatted(eventId, type);
    }

    /**
     * 같은 검증 요청에 서로 다른 전달 이벤트 ID를 부여한다.
     *
     * @return 전달할 JSON
     */
    private String message() {
        return """
            {"eventId":"%s","eventType":"ProblemValidationRequested","schemaVersion":"v1",
             "occurredAt":"%s","aggregateId":"2001","correlationId":"request-sqs",
             "validationId":"2001","problemRevisionId":"1001","language":"java","requestHash":"%s"}
            """.formatted(UUID.randomUUID(), "2026-10-31T09:00:00.000000+09:00", "a".repeat(64));
    }

    /**
     * LocalStack 전용 자격 증명을 제공한다.
     *
     * @return 테스트 자격 증명 공급자
     */
    private static StaticCredentialsProvider credentials() {
        return StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test"));
    }

    /**
     * 테스트 리소스 준비와 확인에 사용할 동기 SQS 클라이언트를 생성한다.
     *
     * @return 닫아야 하는 테스트 클라이언트
     */
    private static SqsClient sqsClient() {
        return SqsClient
            .builder()
            .endpointOverride(LOCALSTACK.getEndpoint())
            .region(Region.of(LOCALSTACK.getRegion()))
            .credentialsProvider(credentials())
            .build();
    }
}
