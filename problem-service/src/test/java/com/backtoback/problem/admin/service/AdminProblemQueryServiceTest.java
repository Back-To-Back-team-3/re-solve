package com.backtoback.problem.admin.service;

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
import com.backtoback.problem.admin.dto.AdminProblemDetailResponse;
import com.backtoback.problem.admin.dto.AdminProblemSearchRequest;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.client.JudgeProgressClient;
import com.backtoback.problem.problem.dto.request.ProblemRevisionRequest;
import com.backtoback.problem.problem.service.ProblemAssetContent;
import com.backtoback.problem.problem.service.ProblemAssetObjectReader;
import com.backtoback.problem.problem.service.ProblemAssetObjectWriter;
import com.backtoback.problem.problem.service.ProblemCreateFixtures;
import com.backtoback.problem.problem.service.ProblemCreateService;

/** 실제 DB에서 관리자 회차 선택·권한과 불변 원문 조회를 검증한다. */
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
class AdminProblemQueryServiceTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("problem_db");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @Autowired
    private ProblemCreateService creates;
    @Autowired
    private AdminProblemQueryStore queries;
    @Autowired
    private AdminProblemQueryService details;
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

    /** 최신 회차를 먼저 고른 뒤 제목을 필터링하고 과거 원문을 보존한다. */
    @Test
    @DisplayName("최신 회차 검색은 이전 제목에 매칭되지 않고 지정 회차 원문은 유지된다")
    void fixedRevisionAndLatestFilter() {
        var input = ProblemCreateFixtures.request();
        var first = creates.create(9001, UUID.randomUUID().toString(), input, null);
        long id = Long.parseLong(first.problemId());
        var next
            = creates
                .revise(
                    id,
                    9001,
                    UUID.randomUUID().toString(),
                    new ProblemRevisionRequest(
                        1,
                        "새 제목",
                        "새 설명",
                        input.constraints(),
                        input.examples(),
                        2,
                        input.tagIds(),
                        input.functionSpec(),
                        input.executionLimits(),
                        input.testCases()
                    ),
                    "query224",
                    null
                );
        assertThat(
            queries.list(new AdminProblemSearchRequest("두 배", null, null, null, null, true, 0, 20, null)).getContent()
        ).noneMatch(item -> item.problem().getId() == id);
        assertThat(
            queries.list(new AdminProblemSearchRequest("두 배", null, null, null, null, false, 0, 20, null)).getContent()
        ).anyMatch(item -> item.problem().getId() == id);
        var old = AdminProblemDetailResponse.from(details.read(id, Long.parseLong(first.problemRevisionId()), 9001));
        assertThat(old.description()).isEqualTo("설명");
        assertThat(old.testCases()).hasSize(2);
        assertThat(old.testCases().get(1).isSample()).isFalse();
        var latest = AdminProblemDetailResponse.from(details.read(id, null, 9001));
        assertThat(latest.problemRevisionId()).isEqualTo(next.problemRevisionId());
        assertThat(latest.description()).isEqualTo("새 설명");
        assertThat(queries.history(id, 9001, 0, 20, "revisionNumber,desc").getContent())
            .extracting(value -> value.revision().getRevisionNumber())
            .containsExactly(2, 1);
    }

    /** 작성자 불일치와 다른 문제의 회차를 거부하고 원문 조회 실패를 전파한다. */
    @Test
    @DisplayName("작성 권한과 회차 소속을 검사하고 파일 조회 실패는 503으로 처리한다")
    void ownershipAndDependencyFailure() {
        var first = creates.create(9002, UUID.randomUUID().toString(), ProblemCreateFixtures.request(), null);
        var other = creates.create(9002, UUID.randomUUID().toString(), ProblemCreateFixtures.request(), null);
        long id = Long.parseLong(first.problemId());
        assertThatThrownBy(() -> details.read(id, null, 9003))
            .isInstanceOfSatisfying(
                ProblemException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.AUTH_ACCESS_DENIED)
            );
        assertThatThrownBy(() -> details.read(id, Long.parseLong(other.problemRevisionId()), 9002))
            .isInstanceOfSatisfying(
                ProblemException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ProblemErrorCode.PROBLEM_REVISION_NOT_FOUND)
            );
        given(reader.read(anyString(), anyString(), anyString())).willReturn(new byte[0]);
        assertThatThrownBy(() -> details.read(id, null, 9002))
            .isInstanceOfSatisfying(
                ProblemException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE)
            );
    }
}
