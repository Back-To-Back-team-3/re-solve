package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

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
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.client.JudgeProgressClient;
import com.backtoback.problem.problem.dto.request.ProblemRevisionRequest;
import com.backtoback.problem.problem.dto.request.ProblemValidationRequest;
import com.backtoback.problem.problem.dto.response.JudgingDataResponse;
import com.backtoback.problem.problem.dto.response.JudgingValidationResponse;

/** 실제 DB에서 고정 회차의 채점 자료와 문제 소속 검증을 확인한다. */
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
class ProblemJudgingStoreTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("problem_db");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @Autowired
    private ProblemCreateService creates;
    @Autowired
    private ProblemJudgingStore queries;
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

    /** 최신 작성 회차와 관계없이 고정 회차의 테스트 파일 버전과 검증 환경을 제공한다. */
    @Test
    @DisplayName("보관 문제의 이전 회차도 원래 제목과 전체 테스트 및 통과 환경을 반환한다")
    void fixedEnvironment() {
        var input = ProblemCreateFixtures.request();
        var first = creates.create(9201, UUID.randomUUID().toString(), input, null);
        long id = Long.parseLong(first.problemId());
        long revision = Long.parseLong(first.problemRevisionId());
        var accepted
            = validation
                .accept(
                    id,
                    revision,
                    9201,
                    UUID.randomUUID().toString(),
                    new ProblemValidationRequest("java", "class Solution {}"),
                    "query226",
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
                9201,
                UUID.randomUUID().toString(),
                new ProblemRevisionRequest(
                    1,
                    "새 회차",
                    input.description(),
                    input.constraints(),
                    input.examples(),
                    3,
                    input.tagIds(),
                    input.functionSpec(),
                    input.executionLimits(),
                    input.testCases()
                ),
                "query226",
                null
            );
        jdbc.update("UPDATE problems SET visibility='ARCHIVED' WHERE id=?", id);
        var response = JudgingDataResponse.from(queries.read(id, revision));
        assertThat(response.problemTitle()).isEqualTo(input.title());
        assertThat(response.problemDifficulty()).isEqualTo(1);
        assertThat(response.cases()).hasSize(2).allSatisfy(test -> {
            assertThat(test.asset().objectVersion()).isEqualTo("version-1");
        });
        assertThat(response.cases().get(1).isSample()).isFalse();
        assertThat(response.executionProfiles().getFirst().runtimeKey()).isEqualTo("a".repeat(64));
        jdbc.update("UPDATE problem_validations SET runtime_key=NULL WHERE id=?", accepted.validationId());
        assertThatThrownBy(() -> queries.read(id, revision)).isInstanceOfSatisfying(ProblemException.class, failure -> {
            assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE);
            assertThat(failure).hasCauseInstanceOf(IllegalStateException.class);
            assertThat(failure.getCause()).hasMessage("통과 실행 환경이 없습니다.");
        });
    }

    /** 버전 관리 미사용 파일도 연결 상태와 체크섬이 확인되면 조회한다. */
    @Test
    @DisplayName("버전 없는 연결 파일도 사전 검증과 채점 자료에 반환한다")
    void unversionedAssets() {
        var created = creates.create(9203, UUID.randomUUID().toString(), ProblemCreateFixtures.request(), null);
        long id = Long.parseLong(created.problemId());
        long revision = Long.parseLong(created.problemRevisionId());
        jdbc.update("UPDATE problem_test_assets SET object_version=NULL WHERE problem_id=?", id);

        var snapshot = queries.read(id, revision);
        var data = JudgingDataResponse.from(snapshot);
        var validationResponse = JudgingValidationResponse.from(snapshot);
        assertThat(data.cases()).hasSize(2).allSatisfy(test -> {
            assertThat(test.asset().objectVersion()).isNull();
            assertThat(test.asset().objectKey()).isNotBlank();
            assertThat(test.asset().sha256()).hasSize(64);
        });
        assertThat(validationResponse.totalCount()).isEqualTo(2);
        assertThat(validationResponse.testAssetIds())
            .containsExactlyElementsOf(data.cases().stream().map(test -> test.testAssetId()).toList());
    }

    /** 다른 문제의 회차 조회를 거부한다. */
    @Test
    @DisplayName("채점 자료는 문제 존재와 회차 소속을 검사한다")
    void rejectsWrongRevision() {
        var first = creates.create(9202, UUID.randomUUID().toString(), ProblemCreateFixtures.request(), null);
        var other = creates.create(9202, UUID.randomUUID().toString(), ProblemCreateFixtures.request(), null);
        assertThatThrownBy(
            () -> queries.read(Long.parseLong(first.problemId()), Long.parseLong(other.problemRevisionId()))
        )
            .isInstanceOfSatisfying(
                ProblemException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ProblemErrorCode.PROBLEM_REVISION_NOT_FOUND)
            );
        assertThatThrownBy(() -> queries.read(999999, Long.parseLong(first.problemRevisionId())))
            .isInstanceOfSatisfying(
                ProblemException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ProblemErrorCode.PROBLEM_NOT_FOUND)
            );
    }
}
