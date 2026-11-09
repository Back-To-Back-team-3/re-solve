package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

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
import com.backtoback.common.web.security.user.GatewayUser;
import com.backtoback.common.web.security.user.UserRole;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.client.JudgeProgressClient;
import com.backtoback.problem.problem.controller.ProblemContentController;
import com.backtoback.problem.problem.dto.request.ProblemValidationRequest;
import com.backtoback.problem.problem.dto.response.ProblemContentResponse;
import com.backtoback.problem.problem.dto.response.StarterCodeResponse;

/** 실제 DB에서 공개·고정 회차 본문과 연결 이미지 조회를 검증한다. */
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
class ProblemContentServiceTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("problem_db");
    @Container
    private static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);
    @Autowired
    private ProblemCreateService creates;
    @Autowired
    private ProblemContentService queries;
    @Autowired
    private ProblemContentController controller;
    @MockitoBean
    private com.backtoback.problem.problem.client.JudgeStarterCodeClient starter;
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

    /** 공개 본문은 숨김 원문을 읽지 않으며 내부 고정 조회는 보관 후에도 유지된다. */
    @Test
    @DisplayName("본문 조회는 공개 예제만 읽고 외부 비공개 접근을 차단한다")
    void publicSamplesAndFixedAccess() {
        var first = creates.create(9301, UUID.randomUUID().toString(), ProblemCreateFixtures.request(), null);
        long id = Long.parseLong(first.problemId());
        long revision = Long.parseLong(first.problemRevisionId());
        var accepted
            = validation
                .accept(
                    id,
                    revision,
                    9301,
                    UUID.randomUUID().toString(),
                    new ProblemValidationRequest("java", "secret code"),
                    "query227",
                    null
                );
        jdbc
            .update(
                "UPDATE problem_validations SET result_status='PASSED',runtime_key=REPEAT('a',64),runner_version='v1'"
                    + " WHERE id=?",
                accepted.validationId()
            );
        jdbc.update("UPDATE problems SET visibility='PUBLIC' WHERE id=?", id);
        given(starter.read(any(), any())).willReturn(List.of(new StarterCodeResponse("java", "starter")));
        jdbc.update("UPDATE problem_revisions SET created_at='2026-11-07 10:20:30' WHERE id=?", revision);
        clearInvocations(reader);
        var response = ProblemContentResponse.from(queries.read(id, null, null));
        assertThat(response.starterCodes()).containsExactly(new StarterCodeResponse("java", "starter"));
        assertThat(response.samples()).hasSize(1);
        assertThat(response.samples().getFirst().expectedReturnText()).isEqualTo("2");
        verify(reader, times(2)).read(anyString(), anyString(), anyString());
        var mapper = tools.jackson.databind.json.JsonMapper.builder().build();
        jdbc.update("""
            INSERT INTO member_problem_statuses(member_id,problem_id,is_solved,attempt_count,created_at,updated_at)
            VALUES(9401,?,true,1,NOW(),NOW()),(9402,?,false,1,NOW(),NOW())
            """, id, id);
        var detail = controller.content(id, new GatewayUser("9401", UserRole.USER)).getBody().data();
        var json = mapper.valueToTree(detail);
        assertThat(json.get("starterCodes").size()).isEqualTo(1);
        assertThat(json.get("starterCodes").get(0).get("language").asString()).isEqualTo("java");
        assertThat(json.get("starterCodes").get(0).get("sourceCode").asString()).isEqualTo("starter");
        assertThat(controller.content(id, new GatewayUser("9402", UserRole.USER)).getBody().data().solvedStatus())
            .isEqualTo("UNSOLVED");
        assertThat(controller.content(id, new GatewayUser("9403", UserRole.USER)).getBody().data().solvedStatus())
            .isEqualTo("UNSOLVED");
        assertThat(json.has("content")).isFalse();
        assertThat(json.get("publishedAt").asString()).isEqualTo("2026-11-07T10:20:30+09:00");
        assertThat(mapper.valueToTree(response).get("publishedAt").asString()).isEqualTo("2026-11-07T10:20:30+09:00");
        assertThat(json.get("problemId").asString()).isEqualTo(first.problemId());
        assertThat(json.get("solvedStatus").asString()).isEqualTo("SOLVED");
        jdbc.update("UPDATE problems SET visibility='ARCHIVED' WHERE id=?", id);
        assertThatThrownBy(() -> queries.read(id, null, null))
            .isInstanceOfSatisfying(
                ProblemException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ProblemErrorCode.PROBLEM_NOT_FOUND)
            );
        assertThat(queries.read(id, revision, "EXAM:1").fixed().revision().getId()).isEqualTo(revision);
        assertThatThrownBy(() -> queries.read(id, revision, "EXAM:0"))
            .isInstanceOfSatisfying(
                ProblemException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_INVALID_REQUEST)
            );
    }

    /** 본문 연결과 문제 소속이 모두 일치하는 이미지에만 접근한다. */
    @Test
    @DisplayName("이미지는 지정 회차 본문 연결을 확인하고 실제 MIME으로 반환한다")
    void linkedImageOnly() {
        var first = creates.create(9302, UUID.randomUUID().toString(), ProblemCreateFixtures.request(), null);
        long id = Long.parseLong(first.problemId());
        long revision = Long.parseLong(first.problemRevisionId());
        var image
            = ProblemAssetContent
                .fromBytes(
                    new byte[] {
                        1,
                        2,
                        3
                    }
                );
        String key = "images/" + UUID.randomUUID();
        objects.put(key, image.bytes());
        jdbc.update("""
            INSERT INTO problem_images(problem_id,owner_id,status,object_key,object_version,sha256,content_type,
                size_bytes,upload_expires_at,uploaded_at,upload_finished_at,created_at,updated_at)
            VALUES(?,9302,'LINKED',?,'version-1',?,'image/png',3,NOW(),NOW(),NOW(),NOW(),NOW())
            """, id, key, image.sha256());
        long imageId = jdbc.queryForObject("SELECT id FROM problem_images WHERE object_key=?", Long.class, key);
        assertThatThrownBy(() -> queries.image(id, revision, imageId, "EXAM:1"))
            .isInstanceOfSatisfying(
                ProblemException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ProblemErrorCode.PROBLEM_NOT_FOUND)
            );
        jdbc.update("""
            INSERT INTO problem_body_images(body_asset_id,image_id,created_at)
            SELECT body_asset_id,?,NOW() FROM problem_revisions WHERE id=?
            """, imageId, revision);
        var result = queries.image(id, revision, imageId, "EXAM:1");
        assertThat(result.contentType()).isEqualTo("image/png");
        assertThat(result.bytes()).containsExactly(1, 2, 3);
        assertThatThrownBy(() -> queries.image(id, revision, imageId, null))
            .isInstanceOfSatisfying(
                ProblemException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ProblemErrorCode.PROBLEM_NOT_FOUND)
            );
        var other = creates.create(9302, UUID.randomUUID().toString(), ProblemCreateFixtures.request(), null);
        // 본문 연결은 유지해 문제 소속 검사만으로 다른 문제의 이미지를 거부하는지 확인한다.
        jdbc.update("UPDATE problem_images SET problem_id=? WHERE id=?", Long.parseLong(other.problemId()), imageId);
        assertThatThrownBy(() -> queries.image(id, revision, imageId, "EXAM:1"))
            .isInstanceOfSatisfying(
                ProblemException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ProblemErrorCode.PROBLEM_NOT_FOUND)
            );

    }

}
