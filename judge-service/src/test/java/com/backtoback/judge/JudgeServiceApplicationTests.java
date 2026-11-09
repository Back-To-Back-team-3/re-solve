package com.backtoback.judge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.judge.judging.service.JudgeWorkScheduler;
import com.zaxxer.hikari.HikariDataSource;

import software.amazon.awssdk.services.sns.SnsClient;
import software.amazon.awssdk.services.sqs.SqsAsyncClient;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;

@SpringBootTest(
    properties = {
        "spring.flyway.locations=classpath:db/migration,classpath:db/testmigration",
        "spring.datasource.hikari.maximum-pool-size=1"
    }
)
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

    @Autowired
    private ApplicationContext context;

    @Autowired
    private HikariDataSource dataSource;

    @Autowired
    private JpaTransactionManager transactionManager;

    /**
     * 풀이 고갈되면 연결 대기 상한에 도달한 회수 트랜잭션이 쿼리 없이 끝나는지 확인한다.
     *
     * @throws SQLException 테스트에서 연결을 빌리거나 반환하지 못한 경우
     */
    @Test
    @DisplayName("DB 연결 풀이 고갈되어도 채점 회수는 12초 안에 쿼리 없이 종료한다")
    void stopsRecoveryWhenConnectionPoolIsExhausted() throws SQLException {
        var called = new AtomicBoolean();
        var scheduler = new JudgeWorkScheduler(() -> false, () -> {
            called.set(true);
            return false;
        }, Runnable::run, Clock.systemUTC(), transactionManager);
        assertThat(dataSource.getConnectionTimeout()).isEqualTo(2000);
        assertThat(dataSource.getValidationTimeout()).isEqualTo(1000);
        try (var connection = dataSource.getConnection()) {
            assertThat(connection.isClosed()).isFalse();
            long started = System.nanoTime();
            scheduler.expire();
            assertThat(Duration.ofNanos(System.nanoTime() - started))
                .isBetween(Duration.ofSeconds(1), Duration.ofSeconds(12));
            assertThat(called).isFalse();
        }
    }

    /** 기본 비활성 설정에서 원격 실행과 예약 작업이 시작되지 않는지 확인한다. */
    @Test
    @DisplayName("메시징 비활성 시 검증 수신·실행·발행 예약을 등록하지 않는다")
    void disablesValidationExecution() {
        assertThat(context.containsBean("validationMessageListener")).isFalse();
        assertThat(context.containsBean("outboxRelay")).isFalse();
        assertThat(context.containsBean("validationScheduler")).isFalse();
        assertThat(context.containsBean("judgeValidationExecutor")).isFalse();
        assertThat(context.containsBean("judgeValidationScheduler")).isFalse();
    }

    /**
     * 테스트 컨테이너의 DB·Redis·SNS·SQS 연결 정보를 Spring 설정에 등록한다.
     *
     * @param registry 테스트 실행 시 사용할 동적 속성 등록기
     */
    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> REDIS_PASSWORD);
        registry.add("LOCALSTACK_ENDPOINT", LOCALSTACK::getEndpoint);
        registry.add("spring.cloud.aws.region.static", LOCALSTACK::getRegion);
    }

    /**
     * Judge 전용 DB 연결과 Flyway 마이그레이션 적용 결과를 검증한다.
     */
    @Test
    @DisplayName("Judge 전용 DB에 연결하고 최신 마이그레이션을 적용한다")
    void applicationConnectsToJudgeDatabaseAndAppliesMigrations() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("judge_db");
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("20");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM setup_probe", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM shedlock", Integer.class)).isZero();
    }

    /**
     * 설정된 Redis 클라이언트로 값을 저장하고 조회할 수 있는지 검증한다.
     */
    @Test
    void configuredRedisClientCanWriteAndRead() {
        redis.opsForValue().set("judge:setup:probe", "connected", Duration.ofMinutes(1));
        assertThat(redis.opsForValue().get("judge:setup:probe")).isEqualTo("connected");
    }

    /**
     * 설정된 SNS·SQS 클라이언트로 LocalStack에서 메시지를 발행하고 수신할 수 있는지 검증한다.
     */
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

    /**
     * 인증 없이 헬스 체크를 조회할 수 있고 인프라 연결 상태가 정상인지 검증한다.
     *
     * @throws Exception 헬스 체크 요청 처리 중 오류가 발생한 경우
     */
    @Test
    void healthIsAvailableWithoutAuthentication() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

}
