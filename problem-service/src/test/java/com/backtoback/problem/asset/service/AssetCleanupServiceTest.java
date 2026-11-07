package com.backtoback.problem.asset.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

import java.sql.DriverManager;
import java.time.Clock;
import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.AopTestUtils;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.event.service.OutboxRelay;
import com.backtoback.problem.asset.domain.AssetKind;

/** 실제 MySQL에서 일반 제출의 소비 기록과 집계의 원자성·동시성을 검증한다. */
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
class AssetCleanupServiceTest {
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
    private AssetCleanupStore store;
    @Autowired
    private AssetCleanupService service;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    private AssetCleanupStore storeSpy;
    @MockitoBean
    private AssetObjectDeleter objects;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private Clock clock;
    @MockitoBean
    private OutboxRelay relay;

    /** 네 파일 종류의 상태와 업로드 유예를 각각 다시 검사한다. */
    @Test
    @DisplayName("네 종류 파일은 쓰기 종료와 24시간 유예를 만족해야 정리한다")
    void allKinds() {
        for (var kind : AssetKind.values()) {
            var old = asset(kind, "PENDING", true);
            var pending = asset(kind, "PENDING", false);
            var linked = asset(kind, "LINKED", true);
            var recent = asset(kind, "DISCARDED", true);
            jdbc
                .update(
                    "UPDATE " + kind.table() + " SET upload_finished_at=? WHERE id=?",
                    LocalDateTime.now(clock).minusHours(23),
                    recent.id()
                );
            assertThat(service.clean(old)).isEqualTo("deleted");
            assertThat(service.clean(pending)).isEqualTo("unconfirmed");
            assertThat(service.clean(linked)).isEqualTo("skipped");
            assertThat(service.clean(recent)).isEqualTo("skipped");
            assertThat(exists(old)).isFalse();
            assertThat(exists(pending)).isTrue();
        }
    }

    /** 후보 제한은 테이블별이 아니라 전체 파일 종류에 적용한다. */
    @Test
    @DisplayName("한 회차 후보는 네 테이블 합계 500건을 넘지 않는다")
    void candidateLimit() {
        for (int index = 0; index < 501; index++) {
            asset(AssetKind.values()[index % 4], "DISCARDED", true);
        }
        assertThat(store.candidates()).hasSize(500);
    }

    /** S3 삭제 이후 DB 실패도 같은 DELETING에서 다시 시작한다. */
    @Test
    @DisplayName("S3 장애와 삭제 후 DB 장애는 DELETING을 유지하고 다음 실행에서 복구한다")
    void restartRecovery() {
        var candidate = asset(AssetKind.CODE, "DISCARDED", true);
        doThrow(new IllegalStateException("S3 timeout")).when(objects).delete(any(), any(), any());
        assertThatThrownBy(() -> service.clean(candidate)).isInstanceOf(IllegalStateException.class);
        assertThat(state(candidate)).isEqualTo("DELETING");
        reset(objects);
        var target = AopTestUtils.<AssetCleanupStore>getUltimateTargetObject(storeSpy);
        doAnswer(call -> {
            throw new DataAccessResourceFailureException("DB unavailable");
        }).when(target).complete(any());
        assertThatThrownBy(() -> service.clean(candidate)).isInstanceOf(DataAccessResourceFailureException.class);
        assertThat(state(candidate)).isEqualTo("DELETING");
        reset(target);
        assertThat(service.clean(candidate)).isEqualTo("deleted");
        assertThat(exists(candidate)).isFalse();
    }

    /** 실제 연결 참조는 상태가 잘못 저장돼 있어도 보호한다. */
    @Test
    @DisplayName("이전 회차·테스트·본문 이미지·검증 코드 참조는 미연결 상태여도 삭제하지 않는다")
    void referencedFiles() {
        var queries
            = java.util.Map
                .of(
                    AssetKind.BODY,
                    "SELECT body_asset_id FROM problem_revisions LIMIT 1",
                    AssetKind.TEST,
                    "SELECT test_asset_id FROM problem_revision_tests LIMIT 1"
                );
        for (var entry : queries.entrySet()) {
            long id = jdbc.queryForObject(entry.getValue(), Long.class);
            var candidate = new AssetCleanupStore.Candidate(entry.getKey(), id);
            jdbc
                .update(
                    "UPDATE " + entry.getKey().table()
                        + " SET status='PENDING',upload_finished_at=now(6)-INTERVAL 2 DAY WHERE id=?",
                    id
                );
            assertThatThrownBy(() -> store.claim(candidate)).isInstanceOf(IllegalStateException.class);
            jdbc.update("UPDATE " + entry.getKey().table() + " SET status='LINKED' WHERE id=?", id);
        }
        var body = asset(AssetKind.BODY, "PENDING", true);
        var image = asset(AssetKind.IMAGE, "PENDING", true);
        jdbc
            .update(
                "INSERT INTO problem_body_images(body_asset_id,image_id,created_at) VALUES(?,?,now(6))",
                body.id(),
                image.id()
            );
        assertThatThrownBy(() -> store.claim(body)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> store.claim(image)).isInstanceOf(IllegalStateException.class);
        var code = asset(AssetKind.CODE, "PENDING", true);
        jdbc.update("""
            INSERT INTO problem_validations(problem_revision_id,requested_by,language,code_asset_id,trigger_type,
                request_hash,result_status,created_at,updated_at)
            VALUES(1,1,'java',?,'MANUAL',repeat('a',64),'FAILED',now(6),now(6))
            """, code.id());
        assertThatThrownBy(() -> store.claim(code)).isInstanceOf(IllegalStateException.class);
        verify(objects, never()).delete(any(), any(), any());
    }

    /** 선점은 실제 다른 연결 트랜잭션의 잠금에 대기하지 않는다. */
    @Test
    @DisplayName("연결 트랜잭션이 잠근 파일은 건너뛰고 커밋 뒤 실제 참조를 보존한다")
    void realLockRace() throws Exception {
        var candidate = asset(AssetKind.IMAGE, "PENDING", true);
        try (
            var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        ) {
            connection.setAutoCommit(false);
            try (var statement = connection.prepareStatement("SELECT id FROM problem_images WHERE id=? FOR UPDATE")) {
                statement.setLong(1, candidate.id());
                statement.executeQuery().close();
            }
            assertThat(service.clean(candidate)).isEqualTo("skipped");
            try (var statement = connection.prepareStatement("""
                INSERT INTO problem_body_images(body_asset_id,image_id,created_at) VALUES(1,?,now(6))
                """)) {
                statement.setLong(1, candidate.id());
                statement.executeUpdate();
            }
            try (var statement = connection.prepareStatement("UPDATE problem_images SET status='LINKED' WHERE id=?")) {
                statement.setLong(1, candidate.id());
                statement.executeUpdate();
            }
            connection.commit();
        }
        assertThat(service.clean(candidate)).isEqualTo("skipped");
        assertThat(exists(candidate)).isTrue();
    }

    /** 선점 뒤 연결 시도와 참조 오염을 재검증한다. */
    @Test
    @DisplayName("DELETING은 신규 연결을 거부하고 최종 정리도 새 참조가 있으면 롤백한다")
    void deletingProtection() {
        var candidate = asset(AssetKind.IMAGE, "PENDING", true);
        var file = store.claim(candidate).file();
        var image = imageRepository.findById(candidate.id()).orElseThrow();
        assertThatThrownBy(() -> image.linkToProblem(1L, 1L)).isInstanceOf(IllegalStateException.class);
        jdbc
            .update(
                "INSERT INTO problem_body_images(body_asset_id,image_id,created_at) VALUES(1,?,now(6))",
                candidate.id()
            );
        assertThatThrownBy(() -> store.complete(file)).isInstanceOf(IllegalStateException.class);
        assertThat(exists(candidate)).isTrue();
    }

    @Autowired
    private com.backtoback.problem.problem.repository.ProblemImageRepository imageRepository;

    /**
     * 독립 정리 파일을 만든다.
     *
     * @param kind 파일 종류
     * @param status 상태
     * @param finished 쓰기 종료 여부
     * @return 후보
     */
    private AssetCleanupStore.Candidate asset(AssetKind kind, String status, boolean finished) {
        String key = "cleanup-" + java.util.UUID.randomUUID();
        String columns = kind == AssetKind.IMAGE ? ",content_type" : "";
        String values = kind == AssetKind.IMAGE ? ",'image/png'" : "";
        jdbc
            .update(
                "INSERT INTO " + kind.table() + "(problem_id,owner_id,status,object_key,sha256,size_bytes,"
                    + "upload_expires_at,uploaded_at,upload_finished_at,created_at,updated_at" + columns + ")"
                    + " VALUES(1,1,?,?,repeat('a',64),1,now(6)-INTERVAL 1 DAY,now(6)-INTERVAL 2 DAY,?,"
                    + "now(6)-INTERVAL 3 DAY,now(6)" + values + ")",
                status,
                key,
                finished ? LocalDateTime.now(clock).minusHours(25) : null
            );
        return new AssetCleanupStore.Candidate(
            kind,
            jdbc.queryForObject("SELECT id FROM " + kind.table() + " WHERE object_key=?", Long.class, key)
        );
    }

    /**
     * 파일 잔존 여부를 확인한다.
     *
     * @param candidate 파일
     * @return 존재 여부
     */
    private boolean exists(AssetCleanupStore.Candidate candidate) {
        return jdbc
            .queryForObject(
                "SELECT COUNT(*) FROM " + candidate.kind().table() + " WHERE id=?",
                Integer.class,
                candidate.id()
            ) == 1;
    }

    /**
     * 영속된 상태를 확인한다.
     *
     * @param candidate 파일
     * @return 상태
     */
    private String state(AssetCleanupStore.Candidate candidate) {
        return jdbc
            .queryForObject(
                "SELECT status FROM " + candidate.kind().table() + " WHERE id=?",
                String.class,
                candidate.id()
            );
    }
}
