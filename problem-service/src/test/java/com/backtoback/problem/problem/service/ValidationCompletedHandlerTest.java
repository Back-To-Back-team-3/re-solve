package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.sql.DriverManager;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.event.service.EventConsumer;
import com.backtoback.common.event.service.EventMessageDecoder;
import com.backtoback.common.event.service.OutboxRelay;

import io.awspring.cloud.sqs.listener.acknowledgement.Acknowledgement;

/** 실제 MySQL에서 소비 기록·결과·문제 버전·Outbox의 커밋 경계를 검증한다. */
@SpringBootTest(
    properties = {
        "problem.messaging.validation-queue-url=http://localhost:4566/000000000000/problem-validation-queue",
        "PROBLEM_QUEUE_URL=",
        "spring.flyway.locations=classpath:db/migration,classpath:db/testmigration",
        "spring.cloud.aws.region.static=ap-northeast-2",
        "spring.cloud.aws.credentials.access-key=test",
        "spring.cloud.aws.credentials.secret-key=test",
        "problem.image-storage.bucket=test-bucket"
    }
)
@Testcontainers(disabledWithoutDocker = true)
class ValidationCompletedHandlerTest {
    @Container
    private static final MySQLContainer MYSQL
        = new MySQLContainer("mysql:8.4").withDatabaseName("problem_db").withEnv("MYSQL_ROOT_HOST", "%");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

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

    @Autowired
    private EventConsumer consumer;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private EventMessageDecoder decoder;
    @Autowired
    private ValidationCompletedHandler handler;
    @MockitoBean
    private OutboxRelay relay;

    /**
     * 동시 언어 통과와 재전달·상충·늦은 이전 회차의 결과를 한 문제에서 검증한다.
     *
     * @throws Exception 동시 작업 실패
     */
    @Test
    @DisplayName("두 언어 동시 통과와 이전 회차의 늦은 결과가 사용 정보를 되돌리지 않는다")
    void concurrentResults() throws Exception {
        jdbc.update("""
            insert into problem_revisions (id,problem_id,revision_number,body_asset_id,title,difficulty,function_spec,
            time_limit_ms,memory_limit_kb,wall_time_limit_ms,output_limit_kb,author_id,idempotency_key,request_hash,
            response_row_version,created_at) select 8002,problem_id,2,body_asset_id,title,difficulty,function_spec,
            time_limit_ms,memory_limit_kb,wall_time_limit_ms,output_limit_kb,author_id,'revision2',request_hash,
            response_row_version,created_at from problem_revisions where id=1
            """);
        jdbc.update("""
            insert into problem_code_assets(id,problem_id,owner_id,status,object_key,sha256,size_bytes,
            upload_expires_at,created_at,updated_at) values(8001,1,1,'LINKED','code',repeat('a',64),1,now(),now(),now())
            """);
        for (int index = 0; index < 4; index++) {
            jdbc.update("""
                insert into problem_validations(id,problem_revision_id,requested_by,language,code_asset_id,
                trigger_type,request_hash,created_at,updated_at) values(?,?,1,?,8001,'AUTO',repeat('a',64),now(),now())
                """, 8100 + index, index == 2 ? 1 : 8002, index == 1 ? "cpp" : index == 3 ? "python3" : "java");
        }
        var acknowledgements = new AtomicInteger();
        String javaResult = message(8100, 8002, "java");
        String cppResult = message(8101, 8002, "cpp");
        try (
            var executor = Executors.newFixedThreadPool(2);
            var blocker = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())
        ) {
            blocker.setAutoCommit(false);
            try (var statement = blocker.createStatement()) {
                statement.executeQuery("select id from problems where id=1 for update").close();
            }
            var tasks
                = java.util.stream.Stream
                    .of(javaResult, cppResult)
                    .map(body -> executor.submit(() -> consumer.consume(body, acknowledgements::incrementAndGet)))
                    .toList();
            try {
                awaitBothProblemLocks();
            } finally {
                blocker.rollback();
            }
            for (var task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
        }
        consumer.consume(javaResult, acknowledgements::incrementAndGet);
        consumer.consume(message(8100, 8002, "java"), acknowledgements::incrementAndGet);
        consumer.consume(message(8102, 1, "java"), acknowledgements::incrementAndGet);
        assertThatThrownBy(
            () -> consumer
                .consume(
                    message(8100, 8002, "java").replace("b".repeat(64), "c".repeat(64)),
                    acknowledgements::incrementAndGet
                )
        ).isInstanceOf(IllegalArgumentException.class);
        assertThat(acknowledgements.get()).isEqualTo(5);
        assertThat(jdbc.queryForObject("select count(*) from processed_events", Integer.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("select row_version from problems where id=1", Integer.class)).isEqualTo(3);
        var messages = jdbc.queryForList("select payload from outbox_events order by id", String.class);
        assertThat(messages).hasSize(2);
        assertThat(messages.get(0)).contains("REVISION");
        assertThat(messages.get(1)).contains("LANGUAGE_ADDED", "cpp", "java", "8002");
        String rejected
            = message(8103, 8002, "python3")
                .replace("PASSED", "REJECTED")
                .replace("\"" + "b".repeat(64) + "\"", "null")
                .replace("\"v1\",\"finishedAt\"", "null,\"finishedAt\"");
        assertThatThrownBy(
            () -> consumer.consume(rejected.replace("a".repeat(64), "d".repeat(64)), acknowledgements::incrementAndGet)
        ).isInstanceOf(IllegalArgumentException.class);
        for (String key : List.of("runnerVersion", "runtimeKey")) {
            String incomplete = rejected.replace("\"" + key + "\":null,", "");
            assertThatThrownBy(() -> consumer.consume(incomplete, acknowledgements::incrementAndGet))
                .isInstanceOf(IllegalArgumentException.class);
        }
        consumer.consume(rejected, acknowledgements::incrementAndGet);
        assertThat(jdbc.queryForObject("select result_status from problem_validations where id=8103", String.class))
            .isEqualTo("REJECTED");
        assertThat(jdbc.queryForObject("select count(*) from outbox_events", Integer.class)).isEqualTo(2);

    }

    /**
     * 같은 문제 행에서 두 소비 트랜잭션이 실제로 대기할 때까지 기다린다.
     *
     * @throws Exception 잠금 관찰 또는 대기 실패
     */
    private void awaitBothProblemLocks() throws Exception {
        try (
            var observer = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
            var statement = observer.prepareStatement("""
                select count(distinct thread_id) from performance_schema.data_locks
                where object_schema=database() and object_name='problems' and index_name='PRIMARY'
                and lock_type='RECORD' and lock_data='1' and lock_status='WAITING'
                """)
        ) {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            do {
                try (var rows = statement.executeQuery()) {
                    rows.next();
                    if (rows.getInt(1) == 2) {
                        return;
                    }
                }
                Thread.sleep(25);
            } while (System.nanoTime() < deadline);
            throw new AssertionError("두 소비 트랜잭션의 문제 행 잠금 대기를 확인하지 못했습니다.");
        }
    }

    /** 필수 구독에 처리기가 없으면 소비 완료 기록과 ACK를 남기지 않는다. */
    @Test
    @DisplayName("공유 큐의 미구현 필수 이벤트는 재전달을 위해 남긴다")
    void unhandledSubscription() {
        var listener = new ValidationResultListener(consumer, decoder, List.of(handler));
        var ack = mock(Acknowledgement.class);
        String body = message(8100, 8002, "java").replace("ProblemValidationCompleted", "MemberUpdated");
        String id = decoder.decode(body).metadata().eventId().toString();
        assertThatThrownBy(() -> listener.receive(body, ack)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(ack);
        assertThat(jdbc.queryForObject("select count(*) from processed_events where event_id=?", Integer.class, id))
            .isZero();
    }

    /**
     * 공통 헤더와 고정 요청에 대응하는 통과 결과를 생성한다.
     *
     * @param id 검증 ID
     * @param revision 회차 ID
     * @param language 검증 언어
     * @return 원문 메시지
     */
    private String message(int id, int revision, String language) {
        return """
            {"eventId":"%s","eventType":"ProblemValidationCompleted","schemaVersion":"v1",
            "occurredAt":"2026-10-01T00:00:00.000000+09:00","aggregateId":"%d","correlationId":"trace",
            "validationId":"%d","problemRevisionId":"%d","language":"%s","requestHash":"%s","status":"PASSED",
            "runtimeKey":"%s","runnerVersion":"v1","finishedAt":"2026-10-01T00:00:00.000000+09:00"}
            """.formatted(UUID.randomUUID(), id, id, revision, language, "a".repeat(64), "b".repeat(64));
    }
}
