package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.domain.ProblemBodyAsset;
import com.backtoback.problem.problem.domain.ProblemImage;
import com.backtoback.problem.problem.domain.ProblemScope;
import com.backtoback.problem.problem.dto.FunctionSpec;
import com.backtoback.problem.problem.dto.TypeSpec;
import com.backtoback.problem.problem.dto.request.TestCaseRequest;
import com.backtoback.problem.problem.repository.ProblemBodyAssetRepository;
import com.backtoback.problem.problem.repository.ProblemImageRepository;
import com.backtoback.problem.problem.repository.ProblemRepository;
import com.backtoback.problem.problem.repository.ProblemRevisionRepository;
import com.backtoback.problem.tag.repository.TagRepository;

/** 호출자의 트랜잭션에 참여하는 회차 저장과 롤백을 실제 MySQL로 확인한다. */
@SpringBootTest(
    properties = {
        "spring.flyway.locations=classpath:db/migration,classpath:db/testmigration",
        "spring.cloud.aws.region.static=ap-northeast-2",
        "spring.cloud.aws.credentials.access-key=test",
        "spring.cloud.aws.credentials.secret-key=test"
    }
)
class ProblemRegistrationServiceTest {
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("problem_db");
    private static final GenericContainer<?> REDIS
        = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", "test-password");

    @Autowired
    private ProblemRegistrationService service;
    @Autowired
    private ProblemImageUploadTracking imageUploadTracking;
    @Autowired
    private ProblemBodyAssetRepository bodyAssets;
    @Autowired
    private ProblemImageRepository images;
    @Autowired
    private ProblemRevisionRepository revisions;
    @Autowired
    private ProblemRepository problems;
    @Autowired
    private TagRepository tags;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeAll
    static void startInfrastructure() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker가 없어 통합 테스트를 건너뜁니다.");
        MYSQL.start();
        REDIS.start();
    }

    @AfterAll
    static void stopInfrastructure() {
        REDIS.stop();
        MYSQL.stop();
    }

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "test-password");
    }

    @Test
    @DisplayName("같은 관리자와 멱등키의 동시 접수는 하나의 추적 행만 생성한다")
    void concurrentImageReceiptsCreateOneRow() throws Exception {
        String key = "image-race-" + UUID.randomUUID();
        ProblemAssetContent content
            = ProblemAssetContent
                .fromBytes(
                    new byte[] {
                        1,
                        2,
                        3
                    }
                );
        CountDownLatch start = new CountDownLatch(1);
        Callable<String> receive = () -> {
            start.await(30, TimeUnit.SECONDS);
            try {
                try {
                    imageUploadTracking.start(1L, key, content, "image/png");
                    return "created";
                } catch (DataIntegrityViolationException exception) {
                    imageUploadTracking.start(1L, key, content, "image/png");
                    return "created";
                }
            } catch (ProblemException exception) {
                return exception.getErrorCode().getCode();
            }
        };
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<String> first = executor.submit(receive);
            Future<String> second = executor.submit(receive);
            start.countDown();
            assertThat(List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("created", "PROBLEM_IMAGE_UPLOAD_IN_PROGRESS");
        }
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM problem_images WHERE owner_id = ? AND idempotency_key = ?",
                    Integer.class,
                    1L,
                    key
                )
        ).isEqualTo(1);
    }

    @Test
    @DisplayName("호출자 트랜잭션 없이 저장 메서드를 호출하면 거절한다")
    void requiresCallerTransaction() {
        assertThatThrownBy(() -> service.save(null)).isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    @DisplayName("호출자가 롤백하면 새 문제·회차·태그·이미지 연결과 파일 상태가 함께 롤백된다")
    void rollsBackAllReferencesWithCaller() {
        String suffix = UUID.randomUUID().toString();
        LocalDateTime expires = LocalDateTime.of(2030, 1, 2, 0, 0);
        ProblemBodyAsset body
            = bodyAssets
                .saveAndFlush(ProblemBodyAsset.create(1L, "body/" + suffix, null, "a".repeat(64), 100L, expires));
        ProblemImage image
            = images
                .saveAndFlush(
                    ProblemImage.create(1L, "image/" + suffix, null, "b".repeat(64), "image/png", 200L, expires)
                );
        jdbc
            .update(
                "UPDATE problem_body_assets SET uploaded_at = ?, upload_finished_at = ? WHERE id = ?",
                expires.minusHours(2),
                expires.minusHours(1),
                body.getId()
            );
        jdbc
            .update(
                "UPDATE problem_images SET uploaded_at = ?, upload_finished_at = ? WHERE id = ?",
                expires.minusHours(2),
                expires.minusHours(1),
                image.getId()
            );
        Long tagId = tags.findAll().getFirst().getId();
        String firstKey = "first-" + suffix;
        String secondKey = "second-" + suffix;
        Long[] problemId = new Long[1];

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            var first = service.save(command(null, null, body.getId(), image.getId(), tagId, firstKey, "c"));
            problemId[0] = first.problemId();
            assertThat(first.revisionNumber()).isEqualTo(1);
            assertThat(first.replayed()).isFalse();
            assertThat(service.save(command(null, null, body.getId(), image.getId(), tagId, firstKey, "c")).replayed())
                .isTrue();
            var next = service.save(command(first.problemId(), 1, body.getId(), image.getId(), tagId, secondKey, "d"));
            assertThat(next.revisionNumber()).isEqualTo(2);
            assertThat(next.rowVersion()).isEqualTo(2);
            assertThat(
                jdbc
                    .queryForObject(
                        "SELECT COUNT(*) FROM problem_revision_tags WHERE problem_revision_id IN (?, ?)",
                        Integer.class,
                        first.problemRevisionId(),
                        next.problemRevisionId()
                    )
            ).isEqualTo(2);
            assertThat(
                jdbc
                    .queryForObject(
                        "SELECT COUNT(*) FROM problem_body_images WHERE body_asset_id = ?",
                        Integer.class,
                        body.getId()
                    )
            ).isEqualTo(1);
            throw new IllegalStateException("호출자 작업 실패");
        })).isInstanceOf(IllegalStateException.class).hasMessage("호출자 작업 실패");

        assertThat(revisions.findByAuthorIdAndIdempotencyKey(1L, firstKey)).isEmpty();
        assertThat(revisions.findByAuthorIdAndIdempotencyKey(1L, secondKey)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM problems WHERE id = ?", Integer.class, problemId[0]))
            .isZero();
        assertThat(
            jdbc.queryForObject("SELECT status FROM problem_body_assets WHERE id = ?", String.class, body.getId())
        ).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT status FROM problem_images WHERE id = ?", String.class, image.getId()))
            .isEqualTo("PENDING");
    }

    @Test
    @DisplayName("없는 이미지 참조를 거절하고 문제와 본문 연결을 롤백한다")
    void rejectsMissingImageAndRollsBack() {
        Assets assets = createUploadedAssets();
        Long tagId = tags.findAll().getFirst().getId();
        String key = "invalid-" + UUID.randomUUID();

        assertThatThrownBy(
            () -> new TransactionTemplate(transactionManager)
                .execute(status -> service.save(command(null, null, assets.bodyId(), Long.MAX_VALUE, tagId, key, "e")))
        )
            .isInstanceOfSatisfying(
                ProblemException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(ProblemErrorCode.PROBLEM_IMAGE_INVALID)
            );
        assertThat(revisions.findByAuthorIdAndIdempotencyKey(1L, key)).isEmpty();
        assertThat(
            jdbc.queryForObject("SELECT status FROM problem_body_assets WHERE id = ?", String.class, assets.bodyId())
        ).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("같은 멱등키의 다른 요청은 문제 버전보다 먼저 충돌을 검사한다")
    void rejectsIdempotencyConflictBeforeVersion() {
        Assets assets = createUploadedAssets();
        Long tagId = tags.findAll().getFirst().getId();
        String key = "conflict-" + UUID.randomUUID();

        assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
            var first = service.save(command(null, null, assets.bodyId(), assets.imageId(), tagId, key, "a"));
            return service.save(command(first.problemId(), 0, assets.bodyId(), assets.imageId(), tagId, key, "b"));
        }))
            .isInstanceOfSatisfying(
                ProblemException.class,
                exception -> assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT)
            );
        assertThat(revisions.findByAuthorIdAndIdempotencyKey(1L, key)).isEmpty();
    }

    @Test
    @DisplayName("같은 문제의 같은 버전으로 동시에 새 회차를 저장하면 한 요청만 성공한다")
    void serializesConcurrentRevisionWrites() throws Exception {
        Long problemId
            = jdbc.queryForObject("SELECT id FROM problems WHERE author_id = 1 ORDER BY id LIMIT 1", Long.class);
        Long originalRevisionId
            = jdbc
                .queryForObject(
                    "SELECT id FROM problem_revisions WHERE problem_id = ? AND revision_number = 1",
                    Long.class,
                    problemId
                );
        Long tagId = tags.findAll().getFirst().getId();
        Assets first = createUploadedAssets();
        Assets second = createUploadedAssets();
        CountDownLatch start = new CountDownLatch(1);
        String suffix = UUID.randomUUID().toString();
        Callable<String> firstCall
            = revisionWrite(
                start,
                command(problemId, 1, first.bodyId(), null, tagId, "race-a-" + suffix, "e"),
                originalRevisionId
            );
        Callable<String> secondCall
            = revisionWrite(
                start,
                command(problemId, 1, second.bodyId(), null, tagId, "race-b-" + suffix, "f"),
                originalRevisionId
            );

        try (var executor = Executors.newFixedThreadPool(2)) {
            var firstResult = executor.submit(firstCall);
            var secondResult = executor.submit(secondCall);
            start.countDown();
            assertThat(List.of(firstResult.get(30, TimeUnit.SECONDS), secondResult.get(30, TimeUnit.SECONDS)))
                .containsExactlyInAnyOrder("saved", "PROBLEM_VERSION_CONFLICT");
        }
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM problem_revisions WHERE problem_id = ? AND revision_number = 2",
                    Integer.class,
                    problemId
                )
        ).isEqualTo(1);
    }

    /** 이미 읽은 문제의 버전이 다른 트랜잭션에서 변경된 경우를 확인한다. */
    @Test
    @DisplayName("문제를 미리 조회한 트랜잭션에서도 변경된 버전의 새 회차 저장을 거절한다")
    void rejectsStaleProblemAlreadyLoadedInCallerTransaction() throws Exception {
        // given
        Long problemId
            = jdbc.queryForObject("SELECT id FROM problems WHERE author_id = 1 ORDER BY id LIMIT 1", Long.class);
        Integer originalVersion
            = jdbc.queryForObject("SELECT row_version FROM problems WHERE id = ?", Integer.class, problemId);
        Long tagId = tags.findAll().getFirst().getId();
        Assets assets = createUploadedAssets();
        String key = "stale-" + UUID.randomUUID();

        try (var executor = Executors.newSingleThreadExecutor()) {
            // when & then
            assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status -> {
                problems.findById(problemId).orElseThrow();
                try {
                    executor
                        .submit(
                            () -> jdbc
                                .update("UPDATE problems SET row_version = row_version + 1 WHERE id = ?", problemId)
                        )
                        .get(30, TimeUnit.SECONDS);
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
                return service.save(command(problemId, originalVersion, assets.bodyId(), null, tagId, key, "a"));
            }))
                .isInstanceOfSatisfying(
                    ProblemException.class,
                    exception -> assertThat(exception.getErrorCode())
                        .isEqualTo(ProblemErrorCode.PROBLEM_VERSION_CONFLICT)
                );
        } finally {
            jdbc.update("UPDATE problems SET row_version = ? WHERE id = ?", originalVersion, problemId);
        }
        assertThat(revisions.findByAuthorIdAndIdempotencyKey(1L, key)).isEmpty();
    }

    /** 회차 연결 트랜잭션이 끝날 때까지 참조 태그를 삭제할 수 없는지 확인한다. */
    @Test
    @DisplayName("회차 저장 중인 태그의 삭제는 등록 트랜잭션이 끝날 때까지 대기한다")
    void holdsTagLockUntilRegistrationTransactionEnds() throws Exception {
        // given
        Long tagId = tags.findAll().getFirst().getId();
        Assets assets = createUploadedAssets();
        String key = "tag-lock-" + UUID.randomUUID();
        CountDownLatch deleting = new CountDownLatch(1);
        AtomicReference<Future<?>> deletion = new AtomicReference<>();

        try (var executor = Executors.newSingleThreadExecutor()) {
            // when & then
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                service.save(command(null, null, assets.bodyId(), null, tagId, key, "a"));
                deletion
                    .set(
                        executor
                            .submit(
                                () -> new TransactionTemplate(transactionManager).executeWithoutResult(deleteStatus -> {
                                    deleting.countDown();
                                    jdbc.update("DELETE FROM tags WHERE id = ?", tagId);
                                    deleteStatus.setRollbackOnly();
                                })
                            )
                    );
                try {
                    assertThat(deleting.await(30, TimeUnit.SECONDS)).isTrue();
                    assertThatThrownBy(() -> deletion.get().get(500, TimeUnit.MILLISECONDS))
                        .isInstanceOf(TimeoutException.class);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                status.setRollbackOnly();
            });
            deletion.get().get(30, TimeUnit.SECONDS);
        }
    }

    @Test
    @DisplayName("삭제 선점이 먼저 커밋되면 대기한 등록은 이미지를 연결하지 않는다")
    void rejectsImageWhenDeletionClaimsFirst() throws Exception {
        Assets assets = createUploadedAssets();
        Long tagId = tags.findAll().getFirst().getId();
        String key = "image-claim-" + UUID.randomUUID();
        CountDownLatch claimed = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<?> deletion
                = executor.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                    jdbc.update("UPDATE problem_images SET status = 'DELETING' WHERE id = ?", assets.imageId());
                    claimed.countDown();
                    try {
                        if (!release.await(30, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("삭제 선점 대기 시간 초과");
                        }
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(exception);
                    }
                }));
            assertThat(claimed.await(30, TimeUnit.SECONDS)).isTrue();
            Future<?> registration
                = executor
                    .submit(
                        () -> new TransactionTemplate(transactionManager)
                            .execute(
                                status -> service
                                    .save(command(null, null, assets.bodyId(), assets.imageId(), tagId, key, "a"))
                            )
                    );
            try {
                assertThatThrownBy(() -> registration.get(500, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            deletion.get(30, TimeUnit.SECONDS);
            assertThatThrownBy(() -> registration.get(30, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ProblemException.class)
                .satisfies(
                    exception -> assertThat(((ProblemException)exception.getCause()).getErrorCode())
                        .isEqualTo(ProblemErrorCode.PROBLEM_IMAGE_INVALID)
                );
        }
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM problem_body_images WHERE image_id = ?",
                    Integer.class,
                    assets.imageId()
                )
        ).isZero();
    }

    private Callable<String> revisionWrite(
        CountDownLatch start,
        ProblemRegistrationCommand command,
        Long originalRevisionId
    ) {
        return () -> {
            start.await();
            try {
                new TransactionTemplate(transactionManager).execute(status -> {
                    var saved = service.save(command);
                    List<Long> testAssetIds
                        = jdbc
                            .queryForList(
                                "SELECT test_asset_id FROM problem_revision_tests WHERE "
                                    + "problem_revision_id = ? ORDER BY test_asset_id",
                                Long.class,
                                originalRevisionId
                            );
                    for (Long testAssetId : testAssetIds) {
                        jdbc
                            .queryForObject(
                                "SELECT id FROM problem_test_assets WHERE id = ? FOR UPDATE",
                                Long.class,
                                testAssetId
                            );
                    }
                    // 이미지 잠금이 없는 저장에서 호출자가 기존 테스트 구성을 같은 트랜잭션에 연결한다.
                    jdbc
                        .update(
                            "INSERT INTO problem_revision_tests (problem_revision_id, test_asset_id, test_order, "
                                + "is_sample, created_at) SELECT ?, test_asset_id, test_order, is_sample, created_at "
                                + "FROM problem_revision_tests WHERE problem_revision_id = ?",
                            saved.problemRevisionId(),
                            originalRevisionId
                        );
                    return saved;
                });
                return "saved";
            } catch (ProblemException exception) {
                return exception.getErrorCode().getCode();
            }
        };
    }

    private Assets createUploadedAssets() {
        String suffix = UUID.randomUUID().toString();
        LocalDateTime expires = LocalDateTime.of(2030, 1, 2, 0, 0);
        ProblemBodyAsset body
            = bodyAssets
                .saveAndFlush(ProblemBodyAsset.create(1L, "body/" + suffix, null, "a".repeat(64), 100L, expires));
        ProblemImage image
            = images
                .saveAndFlush(
                    ProblemImage.create(1L, "image/" + suffix, null, "b".repeat(64), "image/png", 200L, expires)
                );
        jdbc
            .update(
                "UPDATE problem_body_assets SET uploaded_at = ?, upload_finished_at = ? WHERE id = ?",
                expires.minusHours(2),
                expires.minusHours(1),
                body.getId()
            );
        jdbc
            .update(
                "UPDATE problem_images SET uploaded_at = ?, upload_finished_at = ? WHERE id = ?",
                expires.minusHours(2),
                expires.minusHours(1),
                image.getId()
            );
        return new Assets(body.getId(), image.getId());
    }

    private record Assets(Long bodyId, Long imageId) {
    }

    private ProblemRegistrationCommand command(
        Long problemId,
        Integer version,
        Long bodyId,
        Long imageId,
        Long tagId,
        String key,
        String hashCharacter
    ) {
        TypeSpec integer = new TypeSpec(TypeSpec.Kind.INT32, null, null, null, null);
        FunctionSpec spec
            = new FunctionSpec("solution", List.of(new FunctionSpec.Parameter("value", integer)), integer);
        List<TestCaseRequest> cases
            = List
                .of(
                    new TestCaseRequest(true, Map.of("value", "1"), "1"),
                    new TestCaseRequest(false, Map.of("value", "2"), "2")
                );
        return new ProblemRegistrationCommand(
            problemId,
            version,
            problemId == null ? ProblemScope.GENERAL : null,
            1L,
            key,
            hashCharacter.repeat(64),
            bodyId,
            "a".repeat(64),
            100L,
            "문제 제목",
            1,
            spec,
            cases,
            2000,
            262144,
            7000,
            64,
            List.of(tagId),
            imageId == null ? "본문" : "![이미지](image:" + imageId + ")",
            "제한",
            "예시"
        );
    }
}
