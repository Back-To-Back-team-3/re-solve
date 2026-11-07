package com.backtoback.problem.admin.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;

import java.time.LocalDateTime;
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
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.admin.dto.AdminProblemDetailResponse;
import com.backtoback.problem.admin.dto.AdminProblemSearchRequest;
import com.backtoback.problem.admin.dto.AdminRevisionSummaryResponse;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.client.JudgeProgressClient;
import com.backtoback.problem.problem.dto.request.ProblemRevisionRequest;
import com.backtoback.problem.problem.dto.request.ProblemValidationRequest;
import com.backtoback.problem.problem.dto.response.JudgeValidationProgressResponse;
import com.backtoback.problem.problem.repository.ProblemValidationRepository;
import com.backtoback.problem.problem.service.ProblemAssetContent;
import com.backtoback.problem.problem.service.ProblemAssetObjectReader;
import com.backtoback.problem.problem.service.ProblemAssetObjectWriter;
import com.backtoback.problem.problem.service.ProblemCreateFixtures;
import com.backtoback.problem.problem.service.ProblemCreateService;
import com.backtoback.problem.problem.service.ProblemValidationService;

import tools.jackson.databind.ObjectMapper;

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
    private ObjectMapper mapper;
    @Autowired
    private ProblemValidationService validationService;
    @Autowired
    private ProblemValidationRepository validations;
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
        assertThat(mapper.readTree(mapper.writeValueAsString(old)).get("publishedAt").asString()).endsWith("+09:00");
        var historical
            = AdminRevisionSummaryResponse
                .from(queries.history(id, 9001, 0, 20, "revisionNumber,desc").getContent().getFirst());
        assertThat(mapper.readTree(mapper.writeValueAsString(historical)).get("publishedAt").asString())
            .endsWith("+09:00");
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
        var storageFailure = new IllegalStateException("storage unavailable");
        given(reader.read(anyString(), anyString(), anyString())).willThrow(storageFailure);
        assertThatThrownBy(() -> details.read(id, null, 9002))
            .isInstanceOfSatisfying(
                ProblemException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE)
            )
            .hasCause(storageFailure);
    }

    /** 통과 코드는 보존하면서 최신 검증의 진행과 작성·사용 회차를 각각 조회한다. */
    @Test
    @DisplayName("통과 코드를 유지하고 최신 요청의 진행과 서로 다른 작성·사용 회차를 반환한다")
    void passedCodeAndLatestProgress() {
        var input = ProblemCreateFixtures.request();
        var created = creates.create(9004, UUID.randomUUID().toString(), input, null);
        long problemId = Long.parseLong(created.problemId());
        long revisionId = Long.parseLong(created.problemRevisionId());
        var passed
            = validationService
                .accept(
                    problemId,
                    revisionId,
                    9004,
                    UUID.randomUUID().toString(),
                    new ProblemValidationRequest("java", "passed code"),
                    "query224",
                    null
                );
        var latest
            = validationService
                .accept(
                    problemId,
                    revisionId,
                    9004,
                    UUID.randomUUID().toString(),
                    new ProblemValidationRequest("python3", "latest code"),
                    "query224",
                    null
                );
        var validation = validations.findById(Long.parseLong(passed.validationId())).orElseThrow();
        validation.complete("PASSED", "a".repeat(64), "runner-test", LocalDateTime.of(2026, 11, 7, 12, 0));
        validations.saveAndFlush(validation);
        var progress
            = new JudgeValidationProgressResponse(
                latest.validationId(),
                created.problemRevisionId(),
                "python3",
                "RUNNING",
                List.of(),
                null,
                null
            );
        given(judge.read(latest.validationId(), created.problemRevisionId(), "python3")).willAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return progress;
        });
        var result = AdminProblemDetailResponse.from(details.read(problemId, revisionId, 9004));
        assertThat(result.referenceSolutions())
            .filteredOn(code -> code.language().equals("java"))
            .singleElement()
            .satisfies(code -> assertThat(code.sourceCode()).isEqualTo("passed code"));
        assertThat(result.supportedLanguages()).containsExactly("java");
        assertThat(result.referenceSolutions())
            .filteredOn(code -> code.language().equals("python3"))
            .singleElement()
            .satisfies(code -> assertThat(code.sourceCode()).isEqualTo("latest code"));
        assertThat(result.languageResults())
            .filteredOn(item -> item.language().equals("java"))
            .singleElement()
            .satisfies(item -> assertThat(item.resultApplied()).isTrue());
        assertThat(result.languageResults())
            .filteredOn(item -> item.language().equals("python3"))
            .singleElement()
            .satisfies(item -> {
                assertThat(item.validationId()).isEqualTo(latest.validationId());
                assertThat(item.status()).isEqualTo("RUNNING");
                assertThat(item.resultApplied()).isFalse();
            });
        given(judge.read(latest.validationId(), created.problemRevisionId(), "python3")).willReturn(null);
        var requested = AdminProblemDetailResponse.from(details.read(problemId, revisionId, 9004));
        assertThat(requested.languageResults())
            .filteredOn(item -> item.language().equals("python3"))
            .singleElement()
            .satisfies(item -> {
                assertThat(item.status()).isEqualTo("REQUESTED");
                assertThat(item.tests()).isEmpty();
            });
        var next
            = creates
                .revise(
                    problemId,
                    9004,
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
        var newer = AdminProblemDetailResponse.from(details.read(problemId, null, 9004));
        assertThat(newer.problemRevisionId()).isEqualTo(next.problemRevisionId());
        assertThat(newer.currentProblemRevisionId()).isEqualTo(created.problemRevisionId());
        assertThat(newer.supportedLanguages()).isEmpty();
        var cause = new IllegalStateException("judge unavailable");
        var failure = new ProblemException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE, cause);
        given(judge.read(latest.validationId(), created.problemRevisionId(), "python3")).willThrow(failure);
        assertThatThrownBy(() -> details.read(problemId, revisionId, 9004)).isSameAs(failure).hasCause(cause);
    }
}
