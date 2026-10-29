package com.backtoback.problem.problem.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.problem.problem.domain.Problem;
import com.backtoback.problem.problem.domain.ProblemBodyAsset;
import com.backtoback.problem.problem.domain.ProblemBodyImage;
import com.backtoback.problem.problem.domain.ProblemImage;
import com.backtoback.problem.problem.domain.ProblemRevision;
import com.backtoback.problem.problem.domain.ProblemScope;
import com.backtoback.problem.problem.domain.ProblemVisibility;

/**
 * 문제 패키지 Repository 5개가 추가한 조회 메서드를 실제 MySQL에 대해 저장·조회로 확인한다.
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
    private ProblemImageRepository images;

    @Autowired
    private ProblemBodyImageRepository bodyImages;

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
        jdbc.update("DELETE FROM problem_body_images");
        jdbc.update("DELETE FROM problem_revisions");
        jdbc.update("DELETE FROM problem_images");
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
}
