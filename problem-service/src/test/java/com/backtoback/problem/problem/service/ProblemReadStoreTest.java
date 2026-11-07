package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.event.service.OutboxRelay;
import com.backtoback.problem.problem.client.JudgeProgressClient;
import com.backtoback.problem.problem.dto.request.ProblemRevisionRequest;
import com.backtoback.problem.problem.dto.request.ProblemSearchRequest;
import com.backtoback.problem.problem.dto.request.ProblemValidationRequest;
import com.backtoback.problem.problem.dto.response.ProblemSummaryListResponse;

/** 실제 DB에서 통과 사용 회차 검색과 목적별 편입 요약을 검증한다. */
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
class ProblemReadStoreTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("problem_db");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @Autowired
    private ProblemCreateService creates;
    @Autowired
    private ProblemReadStore queries;
    @Autowired
    private org.springframework.jdbc.core.JdbcTemplate jdbc;
    @Autowired
    private ProblemValidationService validation;
    @MockitoBean
    private JudgeProgressClient judge;
    @MockitoBean
    private OutboxRelay scheduledRelay;
    @MockitoBean
    private ProblemAssetObjectWriter writer;
    @MockitoBean
    private ProblemAssetObjectReader reader;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

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
        given(writer.put(anyString(), anyString(), any())).willAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            String key = call.getArgument(1);
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

    /** 최신 작성 회차 대신 통과 사용 회차를 선택하고 회원별 해결 상태를 반영한다. */
    @Test
    @DisplayName("검색은 미통과 최신 회차를 제외하고 통과 회차와 회원 풀이 상태를 반환한다")
    void searchesPassedRevision() {
        var input = ProblemCreateFixtures.request();
        var first = creates.create(9101, UUID.randomUUID().toString(), input, null);
        long id = Long.parseLong(first.problemId());
        long revision = Long.parseLong(first.problemRevisionId());
        var accepted
            = validation
                .accept(
                    id,
                    revision,
                    9101,
                    UUID.randomUUID().toString(),
                    new ProblemValidationRequest("java", "class Solution {}"),
                    "query225",
                    null
                );
        jdbc
            .update(
                "UPDATE problem_validations SET result_status='PASSED',runtime_key=REPEAT('a',64),runner_version='v1'"
                    + " WHERE id=?",
                accepted.validationId()
            );
        creates
            .revise(
                id,
                9101,
                UUID.randomUUID().toString(),
                new ProblemRevisionRequest(
                    1,
                    "미통과 제목",
                    input.description(),
                    input.constraints(),
                    input.examples(),
                    3,
                    input.tagIds(),
                    input.functionSpec(),
                    input.executionLimits(),
                    input.testCases()
                ),
                "query225",
                null
            );
        jdbc.update("UPDATE problems SET visibility='PUBLIC' WHERE id=?", id);
        var page = queries.search(77, new ProblemSearchRequest("두 배", 1, null, "UNSOLVED", 0, 20, null));
        assertThat(page.getContent()).anySatisfy(value -> {
            assertThat(value.id()).isEqualTo(id);
            assertThat(value.revision().getId()).isEqualTo(revision);
            assertThat(value.languages()).containsExactly("java");
        });
        jdbc.update("""
            INSERT INTO member_problem_statuses(member_id,problem_id,is_solved,attempt_count,created_at,updated_at)
            VALUES(77,?,true,1,NOW(),NOW())
            """, id);
        assertThat(queries.search(77, new ProblemSearchRequest(null, null, null, "SOLVED", 0, 20, null)).getContent())
            .anyMatch(item -> item.id() == id);
        assertThat(queries.search(78, new ProblemSearchRequest(null, null, null, "SOLVED", 0, 20, null)).getContent())
            .noneMatch(item -> item.id() == id);
        jdbc.update("UPDATE problems SET visibility='ARCHIVED' WHERE id=?", id);
        assertThat(queries.search(77, new ProblemSearchRequest(null, null, null, null, 0, 20, null)).getContent())
            .noneMatch(item -> item.id() == id);
    }

    /** 미존재·미통과 항목과 대회 전용 문제의 가용 조건을 구분한다. */
    @Test
    @DisplayName("선택 요약은 순서와 미존재 항목을 보존하고 대회 전용 문제는 CONTEST만 허용한다")
    void purposeAndMissing() {
        var created = creates.create(9102, UUID.randomUUID().toString(), ProblemCreateFixtures.request(), null);
        long id = Long.parseLong(created.problemId());
        var missing = ProblemSummaryListResponse.from(queries.summaries(List.of(999999L, id), "EXAM")).items();
        assertThat(missing.getFirst()).containsOnlyKeys("problemId", "isAvailable");
        assertThat(missing.get(1)).containsEntry("problemRevisionId", null).containsEntry("isAvailable", false);
        var accepted
            = validation
                .accept(
                    id,
                    Long.parseLong(created.problemRevisionId()),
                    9102,
                    UUID.randomUUID().toString(),
                    new ProblemValidationRequest("java", "code"),
                    "query225",
                    null
                );
        jdbc
            .update(
                "UPDATE problem_validations SET result_status='PASSED',runtime_key=REPEAT('a',64),runner_version='v1'"
                    + " WHERE id=?",
                accepted.validationId()
            );
        jdbc.update("UPDATE problems SET scope='EXAM_ONLY' WHERE id=?", id);
        assertThat(queries.summaries(List.of(id), "CONTEST").getFirst().available()).isTrue();
        assertThat(queries.summaries(List.of(id), "EXAM").getFirst().available()).isFalse();
        jdbc.update("UPDATE problems SET visibility='ARCHIVED' WHERE id=?", id);
        assertThat(queries.summaries(List.of(id), "CONTEST").getFirst().available()).isFalse();
    }
}
