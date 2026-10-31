package com.backtoback.judge.judging.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
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

import com.backtoback.common.event.service.OutboxEventWriter;

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
class ValidationMessageListenerTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    @Container
    static final LocalStackContainer LOCALSTACK
        = new LocalStackContainer("localstack/localstack:3.8").withServices("sns", "sqs");
    private static String inputQueue;
    private static String outputQueue;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private OutboxEventWriter writer;
    @Autowired
    private PlatformTransactionManager manager;
    @Autowired
    private ObjectMapper mapper;

    // 수신·발행 검증 중 작업 자체를 소비하지 않도록 상위 실행만 대체한다.
    @MockitoBean
    private ValidationWorker worker;
    @Autowired
    private ApplicationContext context;

    /** 기존 메시지 활성화 설정이 실제 주기 실행까지 등록하는지 확인한다. */
    @Test
    @DisplayName("메시징 활성화 시 수신·실행·발행과 기한 초과 정리를 함께 등록한다")
    void enablesValidationExecution() {
        assertThat(context.containsBean("validationMessageListener")).isTrue();
        assertThat(context.containsBean("outboxRelay")).isTrue();
        assertThat(context.containsBean("validationScheduler")).isTrue();
        var executor
            = context
                .getBean(
                    "judgeValidationExecutor",
                    org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor.class
                );
        assertThat(executor.getCorePoolSize()).isEqualTo(2);
        assertThat(executor.getMaxPoolSize()).isEqualTo(2);
        await()
            .atMost(Duration.ofSeconds(10))
            .untilAsserted(() -> org.mockito.Mockito.verify(worker, org.mockito.Mockito.atLeastOnce()).runOnce());
    }

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
            inputQueue = sqs.createQueue(request -> request.queueName("problem-validation-queue")).queueUrl();
            outputQueue = sqs.createQueue(request -> request.queueName("problem-queue")).queueUrl();
            var topic = sns.createTopic(request -> request.name("judge-events")).topicArn();
            var queueArn
                = sqs
                    .getQueueAttributes(
                        request -> request.queueUrl(outputQueue).attributeNames(QueueAttributeName.QUEUE_ARN)
                    )
                    .attributes()
                    .get(QueueAttributeName.QUEUE_ARN);
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
                        .attributes(Map.of("RawMessageDelivery", "true"))
                );
            registry.add("judge.messaging.topic-arn", () -> topic);
            registry.add("judge.messaging.validation-queue-url", () -> inputQueue);
        }
    }

    /** 동일 요청의 두 이벤트를 실제 큐로 전달하고 결과를 다른 구독 큐에서 확인한다. */
    @Test
    @DisplayName("SQS 중복 요청을 하나의 작업으로 저장하고 커밋된 결과만 SNS로 전달한다")
    void receivesAcknowledgesAndPublishes() {
        try (var sqs = sqsClient()) {
            sqs.sendMessage(request -> request.queueUrl(inputQueue).messageBody(message()));
            sqs.sendMessage(request -> request.queueUrl(inputQueue).messageBody(message()));
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events", Integer.class)).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM judge_validation_jobs", Integer.class))
                    .isEqualTo(1);
                var counts
                    = sqs
                        .getQueueAttributes(
                            request -> request
                                .queueUrl(inputQueue)
                                .attributeNames(
                                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES,
                                    QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE
                                )
                        )
                        .attributes();
                assertThat(counts.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES)).isEqualTo("0");
                assertThat(counts.get(QueueAttributeName.APPROXIMATE_NUMBER_OF_MESSAGES_NOT_VISIBLE)).isEqualTo("0");
            });
            var payload = new LinkedHashMap<String, Object>();
            payload.put("validationId", "2001");
            payload.put("problemRevisionId", "1001");
            payload.put("language", "java");
            payload.put("requestHash", "a".repeat(64));
            payload.put("status", "ERROR");
            payload.put("runtimeKey", null);
            payload.put("runnerVersion", null);
            payload.put("finishedAt", "2026-10-31T09:10:00.000000+09:00");
            var metadata = new TransactionTemplate(manager).execute(status -> {
                jdbc.update("""
                    UPDATE judge_validation_jobs SET status='ERROR', finished_at=deadline_at,
                    last_error='DEADLINE_EXCEEDED' WHERE validation_id=2001
                    """);
                return writer.append("VALIDATION", "ProblemValidationCompleted", "v1", "2001", "request-sqs", payload);
            });
            await()
                .atMost(Duration.ofSeconds(15))
                .untilAsserted(
                    () -> assertThat(jdbc.queryForObject("SELECT status FROM outbox_events", String.class))
                        .isEqualTo("PUBLISHED")
                );
            var messages = sqs.receiveMessage(request -> request.queueUrl(outputQueue).waitTimeSeconds(5)).messages();
            assertThat(messages).hasSize(1);
            var result = mapper.readTree(messages.getFirst().body());
            assertThat(result.get("eventId").asString()).isEqualTo(metadata.eventId().toString());
            assertThat(result.get("eventType").asString()).isEqualTo("ProblemValidationCompleted");
            assertThat(result.get("correlationId").asString()).isEqualTo("request-sqs");
            assertThat(result.get("status").asString()).isEqualTo("ERROR");
        }
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
