package com.backtoback.notification.event.consumer;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.backtoback.notification.delivery.service.NotificationDeliveryService;
import com.backtoback.notification.event.repository.ProcessedEventRepository;
import com.backtoback.notification.notification.domain.Notification;
import com.backtoback.notification.notification.domain.NotificationType;
import com.backtoback.notification.notification.repository.NotificationRepository;

import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.GetQueueAttributesRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class NotificationSqsIntegrationTest {

    private static final String QUEUE_NAME = "notification-queue";
    private static final String DLQ_NAME = "notification-queue-dlq";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("notification_db");

    @Container
    static final LocalStackContainer LOCALSTACK
        = new LocalStackContainer(DockerImageName.parse("localstack/localstack:3.8")).withServices("sqs");

    private static String queueUrl;
    private static String dlqUrl;

    @Autowired
    private SqsAsyncClient sqsAsyncClient;

    @Autowired
    private ProcessedEventRepository processedEventRepository;

    @Autowired
    private NotificationRepository notificationRepository;

    @Autowired
    private NotificationDeliveryService deliveryService;

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.cloud.aws.sqs.endpoint", () -> LOCALSTACK.getEndpoint().toString());
        registry.add("spring.cloud.aws.region.static", LOCALSTACK::getRegion);
        registry.add("spring.cloud.aws.credentials.access-key", LOCALSTACK::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", LOCALSTACK::getSecretKey);
        registry.add("notification.sqs.queue-name", () -> QUEUE_NAME);
    }

    @BeforeAll
    static void setUpQueues() {
        URI endpoint = LOCALSTACK.getEndpoint();
        try (
            SqsAsyncClient initClient
                = SqsAsyncClient
                    .builder()
                    .endpointOverride(endpoint)
                    .region(Region.of(LOCALSTACK.getRegion()))
                    .credentialsProvider(
                        StaticCredentialsProvider
                            .create(AwsBasicCredentials.create(LOCALSTACK.getAccessKey(), LOCALSTACK.getSecretKey()))
                    )
                    .build()
        ) {
            dlqUrl = initClient.createQueue(CreateQueueRequest.builder().queueName(DLQ_NAME).build()).join().queueUrl();

            String dlqArn
                = initClient
                    .getQueueAttributes(
                        GetQueueAttributesRequest
                            .builder()
                            .queueUrl(dlqUrl)
                            .attributeNames(QueueAttributeName.QUEUE_ARN)
                            .build()
                    )
                    .join()
                    .attributes()
                    .get(QueueAttributeName.QUEUE_ARN);

            String redrivePolicy = "{\"deadLetterTargetArn\":\"" + dlqArn + "\",\"maxReceiveCount\":\"2\"}";

            queueUrl
                = initClient
                    .createQueue(
                        CreateQueueRequest
                            .builder()
                            .queueName(QUEUE_NAME)
                            .attributes(
                                Map
                                    .of(
                                        QueueAttributeName.VISIBILITY_TIMEOUT,
                                        "1",
                                        QueueAttributeName.REDRIVE_POLICY,
                                        redrivePolicy
                                    )
                            )
                            .build()
                    )
                    .join()
                    .queueUrl();
        }
    }

    @Test
    @DisplayName("SQS 도메인 이벤트 수신 시 DB에 알림을 영속화하고 연결된 SSE 스트림으로 즉시 푸시한다")
    void consumeEventPersistsAndPushesSse() throws Exception {
        // given
        Long memberId = 77L;
        SseEmitter emitter = deliveryService.subscribe(memberId, null);

        String messageBody = """
            {
              "eventId": "evt-it-judged-1",
              "eventType": "SubmissionJudged",
              "schemaVersion": "v1",
              "occurredAt": "2026-10-20T21:03:12.123456+09:00",
              "aggregateId": "91023",
              "correlationId": "corr-it-1",
              "submissionId": "91023",
              "userId": "77",
              "problemId": "1001",
              "problemRevisionId": "3",
              "verdict": "AC"
            }
            """;

        // when
        sqsAsyncClient
            .sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(messageBody).build())
            .join();

        // then
        awaitUntil(() -> processedEventRepository.existsByEventId("evt-it-judged-1"), Duration.ofSeconds(10));

        List<Notification> notifications
            = notificationRepository.findByMemberId(memberId, PageRequest.of(0, 10)).getContent();
        assertThat(notifications).hasSize(1);
        Notification saved = notifications.get(0);
        assertThat(saved.getType()).isEqualTo(NotificationType.SUBMISSION_JUDGED);
        assertThat(saved.getTitle()).isEqualTo("채점 완료");
        assertThat(saved.getContent()).isEqualTo("1001번 문제 채점이 완료되었습니다. (결과: AC)");
        assertThat(saved.getLinkUrl()).isEqualTo("/submissions/91023");

        @SuppressWarnings("unchecked") Set<?> earlySendAttempts
            = (Set<?>)ReflectionTestUtils.getField(emitter, "earlySendAttempts");
        assertThat(earlySendAttempts).isNotNull();
        boolean hasNotificationEvent
            = earlySendAttempts
                .stream()
                .map(attempt -> String.valueOf(ReflectionTestUtils.getField(attempt, "data")))
                .anyMatch(data -> data.contains("event:notification") || data.contains("SUBMISSION_JUDGED"));
        assertThat(hasNotificationEvent).isTrue();
    }

    @Test
    @DisplayName("동일한 eventId의 메시지가 중복 수신되어도 알림은 1건만 저장된다")
    void duplicateMessageIsProcessedIdempotently() throws Exception {
        // given
        Long memberId = 88L;
        String messageBody = """
            {
              "eventId": "evt-it-hint-dup",
              "eventType": "HintReady",
              "schemaVersion": "v1",
              "occurredAt": "2026-10-20T21:05:00.000000+09:00",
              "aggregateId": "301",
              "correlationId": "corr-it-2",
              "hintRequestId": "301",
              "memberId": "88",
              "problemId": "1005",
              "level": 1,
              "result": "READY"
            }
            """;

        // when
        sqsAsyncClient
            .sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(messageBody).build())
            .join();
        sqsAsyncClient
            .sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(messageBody).build())
            .join();

        // then
        awaitUntil(() -> processedEventRepository.existsByEventId("evt-it-hint-dup"), Duration.ofSeconds(10));
        Thread.sleep(500L);

        List<Notification> notifications
            = notificationRepository.findByMemberId(memberId, PageRequest.of(0, 10)).getContent();
        assertThat(notifications).hasSize(1);
        assertThat(notifications.get(0).getType()).isEqualTo(NotificationType.HINT_READY);
    }

    @Test
    @DisplayName("처리에 반복 실패한 메시지는 트랜잭션이 롤백되고 재시도 한도 초과 후 DLQ로 이관된다")
    void failingMessageRetriesAndMovesToDlq() throws Exception {
        // given: 필수 필드(verdict)가 누락되어 처리 예외가 발생하는 메시지
        String invalidMessageBody = """
            {
              "eventId": "evt-it-fail-dlq",
              "eventType": "SubmissionJudged",
              "schemaVersion": "v1",
              "occurredAt": "2026-10-20T21:10:00.000000+09:00",
              "aggregateId": "999",
              "correlationId": "corr-it-dlq",
              "submissionId": "999",
              "userId": "99",
              "problemId": "1001"
            }
            """;

        // when
        sqsAsyncClient
            .sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(invalidMessageBody).build())
            .join();

        // then: 재시도(maxReceiveCount=2, visibilityTimeout=1s) 후 DLQ에서 메시지가 수신되어야 한다
        List<Message> dlqMessages = pollDlqMessages(Duration.ofSeconds(15));
        assertThat(dlqMessages).isNotEmpty();
        assertThat(dlqMessages.get(0).body()).contains("evt-it-fail-dlq");
        assertThat(processedEventRepository.existsByEventId("evt-it-fail-dlq")).isFalse();
    }

    private void awaitUntil(java.util.concurrent.Callable<Boolean> condition, Duration timeout) throws Exception {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (Boolean.TRUE.equals(condition.call())) {
                return;
            }
            Thread.sleep(200L);
        }
        assertThat(condition.call()).isTrue();
    }

    private List<Message> pollDlqMessages(Duration timeout) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            List<Message> messages
                = sqsAsyncClient
                    .receiveMessage(
                        ReceiveMessageRequest
                            .builder()
                            .queueUrl(dlqUrl)
                            .maxNumberOfMessages(5)
                            .waitTimeSeconds(1)
                            .build()
                    )
                    .join()
                    .messages();
            if (!messages.isEmpty()) {
                return messages;
            }
            Thread.sleep(300L);
        }
        return List.of();
    }
}
