package com.backtoback.problem.problem.repository;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.problem.problem.domain.Problem;
import com.backtoback.problem.problem.domain.ProblemBodyAsset;
import com.backtoback.problem.problem.domain.ProblemBodyImage;
import com.backtoback.problem.problem.domain.ProblemImage;
import com.backtoback.problem.problem.domain.ProblemRevision;
import com.backtoback.problem.problem.domain.ProblemRevisionTag;
import com.backtoback.problem.problem.domain.ProblemScope;
import com.backtoback.problem.problem.domain.ProblemTestAsset;
import com.backtoback.problem.problem.domain.ProblemVisibility;
import com.backtoback.problem.problem.service.ProblemAssetContent;
import com.backtoback.problem.problem.service.ProblemAssetObjectReader;
import com.backtoback.problem.problem.service.ProblemAssetObjectWriter;
import com.backtoback.problem.problem.service.ProblemAssetUploadService;
import com.backtoback.problem.problem.service.ProblemAssetUploadTracking;
import com.backtoback.problem.tag.repository.TagRepository;

/**
 * 문제 패키지 Repository의 저장·조회와 DB 제약을 실제 MySQL에 대해 확인한다.
 * Redis는 Redisson 클라이언트 빈 기동에 필요해 함께 띄우며, 이 테스트에서 락은 직접 쓰지 않는다.
 */
@SpringBootTest(
    properties = {
        "spring.flyway.locations=classpath:db/migration,classpath:db/testmigration",
        "spring.cloud.aws.region.static=ap-northeast-2",
        "spring.cloud.aws.credentials.access-key=test",
        "spring.cloud.aws.credentials.secret-key=test"
    }
)
class ProblemRepositoriesTest {
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("problem_db");

    private static final GenericContainer<?> REDIS
        = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", "test-password");

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private ProblemRepository problems;

    @Autowired
    private ProblemRevisionRepository problemRevisions;

    @Autowired
    private ProblemBodyAssetRepository bodyAssets;

    @Autowired
    private ProblemAssetUploadTracking uploadTracking;

    @Autowired
    private ProblemAssetUploadService uploadService;

    @MockitoBean
    private ProblemAssetObjectWriter objectWriter;

    @MockitoBean
    private ProblemAssetObjectReader objectReader;

    @Autowired
    private ProblemTestAssetRepository testAssets;

    @Autowired
    private TransactionTemplate transactions;

    @Autowired
    private ProblemImageRepository images;

    @Autowired
    private ProblemBodyImageRepository bodyImages;

    @Autowired
    private ProblemRevisionTagRepository problemRevisionTags;

    @Autowired
    private TagRepository tags;

    @BeforeAll
    static void startInfrastructure() {
        Assumptions
            .assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker가 없어 Repository 통합 테스트를 건너뜁니다.");
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

    @BeforeEach
    void clearData() {
        // 시드 데이터(problem_test_assets·problem_revision_tests)가 problems·problem_revisions를 참조하므로
        // 먼저 지워야 FK 위반 없이 정리된다.
        jdbc.update("DELETE FROM problem_revision_tests");
        jdbc.update("DELETE FROM problem_revision_tags");
        jdbc.update("DELETE FROM problem_body_images");
        jdbc.update("DELETE FROM problem_revisions");
        jdbc.update("DELETE FROM problem_images");
        jdbc.update("DELETE FROM problem_test_assets");
        jdbc.update("DELETE FROM problem_body_assets");
        jdbc.update("DELETE FROM problems");
    }

    @Test
    @DisplayName("용도·공개 상태가 일치하는 문제만 조회한다")
    void findsProblemsByScopeAndVisibility() {
        problems.save(Problem.create(1L, ProblemScope.GENERAL));
        problems.save(Problem.create(1L, ProblemScope.EXAM_ONLY));

        var page
            = problems.findByScopeAndVisibility(ProblemScope.GENERAL, ProblemVisibility.PRIVATE, PageRequest.of(0, 10));

        assertThat(page.getContent()).hasSize(1);
        assertThat(page.getContent().get(0).getScope()).isEqualTo(ProblemScope.GENERAL);
    }

    @Test
    @Transactional
    @DisplayName("문제와 회차 번호가 일치하는 수정 회차만 조회한다")
    void findsProblemRevisionByProblemIdAndRevisionNumber() {
        Problem problem = problems.save(Problem.create(1L, ProblemScope.GENERAL));
        ProblemBodyAsset bodyAsset
            = bodyAssets
                .save(
                    ProblemBodyAsset
                        .create(
                            1L,
                            "problem/body/key",
                            null,
                            "a".repeat(64),
                            1024L,
                            LocalDateTime.of(2026, 10, 30, 0, 0)
                        )
                );
        problemRevisions
            .save(
                ProblemRevision
                    .create(
                        problem.getId(),
                        1,
                        bodyAsset.getId(),
                        "제목",
                        1,
                        "{}",
                        2000,
                        262144,
                        2500,
                        64,
                        1L,
                        "idempotency-key",
                        "a".repeat(64),
                        1,
                        LocalDateTime.of(2026, 10, 29, 10, 0)
                    )
            );

        assertThat(problemRevisions.findByProblemIdAndRevisionNumber(problem.getId(), 1)).isPresent();
        assertThat(problemRevisions.findByProblemIdAndRevisionNumber(problem.getId(), 2)).isEmpty();
        assertThat(problemRevisions.findFirstByProblemIdOrderByRevisionNumberDesc(problem.getId()))
            .get()
            .extracting(ProblemRevision::getRevisionNumber)
            .isEqualTo(1);
    }

    @Test
    @DisplayName("객체 키가 일치하는 본문 파일만 조회한다")
    void findsProblemBodyAssetByObjectKey() {
        bodyAssets
            .save(
                ProblemBodyAsset
                    .create(1L, "problem/body/key", null, "a".repeat(64), 1024L, LocalDateTime.of(2026, 10, 30, 0, 0))
            );

        assertThat(bodyAssets.findByObjectKey("problem/body/key")).isPresent();
        assertThat(bodyAssets.findByObjectKey("problem/body/other")).isEmpty();
    }

    @Test
    @DisplayName("테스트 파일 행 잠금은 다른 트랜잭션의 상태 변경을 막는다")
    void testAssetWriteLockBlocksConcurrentUpdate() throws Exception {
        ProblemTestAsset asset
            = testAssets
                .saveAndFlush(
                    ProblemTestAsset
                        .create(
                            1L,
                            "problem/test/locked",
                            null,
                            "a".repeat(64),
                            10L,
                            LocalDateTime.of(2026, 11, 2, 0, 0)
                        )
                );
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var holder = executor.submit(() -> transactions.executeWithoutResult(status -> {
                testAssets.findByIdForUpdate(asset.getId()).orElseThrow();
                locked.countDown();
                try {
                    if (!release.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("잠금 해제 신호를 받지 못했습니다.");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            try {
                assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
                assertThatThrownBy(() -> transactions.executeWithoutResult(status -> {
                    jdbc
                        .queryForObject(
                            "SELECT id FROM problem_test_assets WHERE id = ? FOR UPDATE NOWAIT",
                            Long.class,
                            asset.getId()
                        );
                    jdbc.update("UPDATE problem_test_assets SET status = 'DELETING' WHERE id = ?", asset.getId());
                }))
                    .isInstanceOf(DataAccessException.class)
                    .hasRootCauseInstanceOf(SQLException.class)
                    .satisfies(
                        exception -> assertThat(
                            ((SQLException)NestedExceptionUtils.getMostSpecificCause(exception)).getErrorCode()
                        ).isEqualTo(3572)
                    );
            } finally {
                release.countDown();
            }
            holder.get(10, TimeUnit.SECONDS);
        }
    }

    /** 만료 후 종료가 확인된 미참조 파일의 폐기와 종료 시각을 확인한다. */
    @Test
    @DisplayName("만료된 미참조 파일은 쓰기 종료 확인 후 폐기한다")
    void discardsExpiredUnreferencedUpload() {
        // given
        LocalDateTime expiry = LocalDateTime.of(2020, 1, 1, 0, 0);
        var asset
            = bodyAssets.saveAndFlush(ProblemBodyAsset.create(1L, "expired-object", null, "a".repeat(64), 1L, expiry));
        var ticket = new ProblemAssetUploadTracking.UploadTicket(asset.getId(), asset.getObjectKey(), expiry, true);

        // when
        boolean completed = uploadTracking.complete(ticket, null);

        // then
        assertThat(completed).isFalse();
        var stored = bodyAssets.findById(asset.getId()).orElseThrow();
        assertThat(stored.getStatus()).isEqualTo(com.backtoback.problem.problem.domain.ProblemAssetStatus.DISCARDED);
        assertThat(stored.getUploadFinishedAt()).isNotNull();
        assertThat(stored.getUploadedAt()).isNull();
    }

    /** 외부 쓰기 도중 다른 트랜잭션이 추적 행을 잠글 수 있는지 확인한다. */
    @Test
    @DisplayName("S3 쓰기 중 추적 행은 커밋되어 있고 다른 트랜잭션이 잠글 수 있다")
    void uploadDoesNotHoldFileLockDuringWrite() {
        // given
        var content
            = ProblemAssetContent
                .fromBytes(
                    new byte[] {
                        1
                    }
                );
        doAnswer(invocation -> {
            String key = invocation.getArgument(1);
            transactions.executeWithoutResult(status -> {
                Long id
                    = jdbc
                        .queryForObject(
                            "SELECT id FROM problem_body_assets WHERE object_key = ? FOR UPDATE NOWAIT",
                            Long.class,
                            key
                        );
                assertThat(id).isNotNull();
            });
            return null;
        }).when(objectWriter).put(any(), any(), any());

        // when
        Long id = uploadService.upload("bucket", 1L, content, true);

        // then
        assertThat(bodyAssets.findById(id))
            .isPresent()
            .get()
            .extracting(ProblemBodyAsset::getUploadFinishedAt)
            .isNotNull();
    }

    /** 바깥 트랜잭션 롤백 후에도 업로드 추적 행이 남는지 확인한다. */
    @Test
    @DisplayName("업로드 추적 행은 호출 트랜잭션이 롤백돼도 보존된다")
    void preservesUploadTrackingAfterCallerRollback() {
        // given
        var content
            = ProblemAssetContent
                .fromBytes(
                    new byte[] {
                        1
                    }
                );

        // when
        var ticket = transactions.execute(status -> {
            var result = uploadTracking.start(1L, content, true);
            status.setRollbackOnly();
            return result;
        });

        // then
        assertThat(ticket).isNotNull();
        assertThat(bodyAssets.findById(ticket.id()))
            .isPresent()
            .get()
            .extracting(ProblemBodyAsset::getObjectKey)
            .isEqualTo(ticket.key());
    }

    @Test
    @DisplayName("객체 키가 일치하는 이미지 파일만 조회한다")
    void findsProblemImageByObjectKey() {
        images
            .save(
                ProblemImage
                    .create(
                        1L,
                        "problem/image/key",
                        null,
                        "a".repeat(64),
                        "image/png",
                        2048L,
                        LocalDateTime.of(2026, 10, 30, 0, 0)
                    )
            );

        assertThat(images.findByObjectKey("problem/image/key")).isPresent();
        assertThat(images.findByObjectKey("problem/image/other")).isEmpty();
    }

    @Test
    @DisplayName("본문 파일에 연결된 이미지 목록만 조회한다")
    void findsProblemBodyImagesByBodyAssetId() {
        ProblemBodyAsset bodyAsset
            = bodyAssets
                .save(
                    ProblemBodyAsset
                        .create(
                            1L,
                            "problem/body/key",
                            null,
                            "a".repeat(64),
                            1024L,
                            LocalDateTime.of(2026, 10, 30, 0, 0)
                        )
                );
        ProblemBodyAsset otherBodyAsset
            = bodyAssets
                .save(
                    ProblemBodyAsset
                        .create(
                            1L,
                            "problem/body/other-key",
                            null,
                            "a".repeat(64),
                            1024L,
                            LocalDateTime.of(2026, 10, 30, 0, 0)
                        )
                );
        ProblemImage image
            = images
                .save(
                    ProblemImage
                        .create(
                            1L,
                            "problem/image/key",
                            null,
                            "a".repeat(64),
                            "image/png",
                            2048L,
                            LocalDateTime.of(2026, 10, 30, 0, 0)
                        )
                );
        bodyImages
            .save(ProblemBodyImage.create(bodyAsset.getId(), image.getId(), LocalDateTime.of(2026, 10, 30, 0, 0)));

        assertThat(bodyImages.findByBodyAssetId(bodyAsset.getId())).hasSize(1);
        assertThat(bodyImages.findByBodyAssetId(otherBodyAsset.getId())).isEmpty();
    }

    @Test
    @DisplayName("문제 회차에 태그를 연결해 저장하고 회차 ID로 조회한다")
    void savesAndFindsProblemRevisionTags() {
        ProblemRevision revision = createRevision();
        Long tagId = tags.findAll().get(0).getId();
        LocalDateTime createdAt = LocalDateTime.of(2026, 11, 1, 10, 0);

        problemRevisionTags.save(ProblemRevisionTag.create(revision.getId(), tagId, createdAt));

        assertThat(problemRevisionTags.findByProblemRevisionIdOrderByTagId(revision.getId()))
            .extracting(ProblemRevisionTag::getTagId)
            .containsExactly(tagId);
    }

    @Test
    @DisplayName("같은 문제 회차와 태그의 중복 연결은 DB 제약으로 거절한다")
    void rejectsDuplicateProblemRevisionTag() {
        ProblemRevision revision = createRevision();
        Long tagId = tags.findAll().get(0).getId();
        LocalDateTime createdAt = LocalDateTime.of(2026, 11, 1, 10, 0);
        problemRevisionTags.saveAndFlush(ProblemRevisionTag.create(revision.getId(), tagId, createdAt));

        assertThatThrownBy(
            () -> problemRevisionTags.saveAndFlush(ProblemRevisionTag.create(revision.getId(), tagId, createdAt))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("존재하지 않는 문제 회차를 참조한 태그 연결은 DB 외래 키로 거절한다")
    void rejectsProblemRevisionTagWithMissingRevision() {
        Long tagId = tags.findAll().get(0).getId();

        assertThatThrownBy(
            () -> problemRevisionTags
                .saveAndFlush(ProblemRevisionTag.create(Long.MAX_VALUE, tagId, LocalDateTime.of(2026, 11, 1, 10, 0)))
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("존재하지 않는 태그를 참조한 연결은 DB 외래 키로 거절한다")
    void rejectsProblemRevisionTagWithMissingTag() {
        ProblemRevision revision = createRevision();

        assertThatThrownBy(
            () -> problemRevisionTags
                .saveAndFlush(
                    ProblemRevisionTag.create(revision.getId(), Long.MAX_VALUE, LocalDateTime.of(2026, 11, 1, 10, 0))
                )
        ).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("같은 문제의 중복 회차 번호는 DB 제약으로 거절한다")
    void rejectsDuplicateRevisionNumber() {
        ProblemRevision first = createRevision();
        ProblemRevision duplicate
            = ProblemRevision
                .create(
                    first.getProblemId(),
                    first.getRevisionNumber(),
                    first.getBodyAssetId(),
                    "중복 회차",
                    1,
                    "{}",
                    2000,
                    262144,
                    7000,
                    64,
                    1L,
                    "duplicate-revision-" + System.nanoTime(),
                    "d".repeat(64),
                    1,
                    LocalDateTime.of(2026, 11, 1, 10, 0)
                );

        assertThatThrownBy(() -> problemRevisions.saveAndFlush(duplicate))
            .isInstanceOf(DataIntegrityViolationException.class);
    }

    private ProblemRevision createRevision() {
        Problem problem = problems.save(Problem.create(1L, ProblemScope.GENERAL));
        ProblemBodyAsset bodyAsset
            = bodyAssets
                .save(
                    ProblemBodyAsset
                        .create(
                            1L,
                            "problem/body/revision-tag-" + System.nanoTime(),
                            null,
                            "b".repeat(64),
                            1024L,
                            LocalDateTime.of(2026, 11, 2, 0, 0)
                        )
                );

        return problemRevisions
            .save(
                ProblemRevision
                    .create(
                        problem.getId(),
                        1,
                        bodyAsset.getId(),
                        "회차 태그 테스트 문제",
                        1,
                        "{}",
                        2000,
                        262144,
                        2500,
                        64,
                        1L,
                        "revision-tag-" + System.nanoTime(),
                        "c".repeat(64),
                        1,
                        LocalDateTime.of(2026, 11, 1, 9, 0)
                    )
            );
    }
}
