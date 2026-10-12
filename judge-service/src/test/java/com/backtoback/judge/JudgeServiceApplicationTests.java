package com.backtoback.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.mysql.MySQLContainer;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

@SpringBootTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/testmigration")
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers
class JudgeServiceApplicationTests {
    private static final String REDIS_PASSWORD = "judge-test-password";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("judge_db");

    @Container
    static final GenericContainer<?> REDIS
        = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", REDIS_PASSWORD);

    @Container
    static final LocalStackContainer LOCALSTACK
        = new LocalStackContainer("localstack/localstack:3.8").withServices("sns", "sqs");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @Autowired
    private StringRedisTemplate redis;

    @Autowired
    private SnsClient sns;

    @Autowired
    private SqsAsyncClient sqs;

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
        registry.add("spring.cloud.aws.endpoint", LOCALSTACK::getEndpoint);
        registry.add("spring.cloud.aws.region.static", LOCALSTACK::getRegion);
        registry.add("spring.cloud.aws.credentials.access-key", LOCALSTACK::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", LOCALSTACK::getSecretKey);
    }

    @Test
    void applicationConnectsToJudgeDatabaseAndAppliesMigrations() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("judge_db");
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM setup_probe", Integer.class)).isZero();
    }

    @Test
    void configuredRedisClientCanWriteAndRead() {
        redis.opsForValue().set("judge:setup:probe", "connected", Duration.ofMinutes(1));
        assertThat(redis.opsForValue().get("judge:setup:probe")).isEqualTo("connected");
    }

    @Test
    void configuredMessagingClientsCanPublishAndReceiveThroughLocalStack() {
        String topicArn = sns.createTopic(request -> request.name("judge-setup-topic")).topicArn();
        String queueUrl = sqs.createQueue(request -> request.queueName("judge-setup-queue")).join().queueUrl();
        String queueArn
            = sqs
                .getQueueAttributes(request -> request.queueUrl(queueUrl).attributeNames(QueueAttributeName.QUEUE_ARN))
                .join()
                .attributes()
                .get(QueueAttributeName.QUEUE_ARN);
        String policy = """
            {
              "Version": "2012-10-17",
              "Statement": [{
                "Effect": "Allow",
                "Principal": {"Service": "sns.amazonaws.com"},
                "Action": "sqs:SendMessage",
                "Resource": "%s",
                "Condition": {"ArnEquals": {"aws:SourceArn": "%s"}}
              }]
            }
            """.formatted(queueArn, topicArn);
        sqs
            .setQueueAttributes(
                request -> request.queueUrl(queueUrl).attributes(Map.of(QueueAttributeName.POLICY, policy))
            )
            .join();
        sns
            .subscribe(
                request -> request
                    .topicArn(topicArn)
                    .protocol("sqs")
                    .endpoint(queueArn)
                    .attributes(Map.of("RawMessageDelivery", "true"))
            );

        sns.publish(request -> request.topicArn(topicArn).message("connected"));

        var messages = sqs.receiveMessage(request -> request.queueUrl(queueUrl).waitTimeSeconds(5)).join().messages();
        assertThat(messages).singleElement().satisfies(message -> assertThat(message.body()).isEqualTo("connected"));
    }

    @Test
    void healthIsAvailableWithoutAuthentication() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

}
