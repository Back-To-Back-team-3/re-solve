package com.backtoback.problem.tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.problem.global.lock.RedisTransactionLock;
import com.backtoback.problem.tag.dto.request.TagCreateRequest;
import com.backtoback.problem.tag.dto.request.TagUpdateRequest;
import com.backtoback.problem.tag.repository.TagRepository;
import com.backtoback.problem.tag.service.TagOrderService;
import com.backtoback.problem.tag.service.TagService;

import tools.jackson.databind.ObjectMapper;

/** 태그 API의 권한·검증·순번·동시성 동작을 검증한다. */
@SpringBootTest(
    properties = {
        // 테스트 클래스패스의 잠금 fixture도 JPA 스키마 검증 대상에 포함된다.
        "spring.flyway.locations=classpath:db/migration,classpath:db/testmigration",
        "spring.cloud.aws.region.static=ap-northeast-2",
        "spring.cloud.aws.credentials.access-key=test",
        "spring.cloud.aws.credentials.secret-key=test"
    }
)
@AutoConfigureMockMvc
class TagApiTest {
    private static final String ADMIN_PATH = "/api/v1/admin/problems/tags";
    private static final String LIST_PATH = "/api/v1/problems/tags";

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("problem_db");

    static final GenericContainer<?> REDIS
        = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", "test-password");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private TagService tagService;
    @MockitoSpyBean
    private TagOrderService tagOrderService;
    @Autowired
    private RedissonClient redisson;
    @Autowired
    private TagRepository tags;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeAll
    static void startInfrastructure() {
        Assumptions.assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker가 없어 태그 API 통합 테스트를 건너뜁니다.");
        try {
            MYSQL.start();
            REDIS.start();
        } catch (RuntimeException | Error startupFailure) {
            try {
                stopInfrastructure();
            } catch (RuntimeException | Error cleanupFailure) {
                startupFailure.addSuppressed(cleanupFailure);
            }
            throw startupFailure;
        }
    }

    @AfterAll
    static void stopInfrastructure() {
        Throwable cleanupFailure = null;
        try {
            if (REDIS.isRunning()) {
                REDIS.stop();
            }
        } catch (RuntimeException | Error exception) {
            cleanupFailure = exception;
        }
        try {
            if (MYSQL.isRunning()) {
                MYSQL.stop();
            }
        } catch (RuntimeException | Error exception) {
            if (cleanupFailure == null) {
                cleanupFailure = exception;
            } else {
                cleanupFailure.addSuppressed(exception);
            }
        }
        if (cleanupFailure instanceof RuntimeException exception) {
            throw exception;
        }
        if (cleanupFailure instanceof Error error) {
            throw error;
        }
    }

    /**
     * 테스트 컨테이너의 연결 정보를 Spring 설정에 등록한다.
     *
     * @param registry 테스트 실행 시 사용할 동적 속성 등록기
     */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "test-password");
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /**
     * 이전 테스트의 태그와 참조 데이터를 정리한다.
     */
    @BeforeEach
    void clearData() {
        // 테스트 전용 참조 테이블로 FK 삭제 제약을 검증한다.
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS tag_usage_probe (
                id BIGINT PRIMARY KEY,
                tag_id BIGINT NOT NULL,
                CONSTRAINT fk_problem_revision_tags_tag FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE RESTRICT
            )
            """);
        jdbc.update("DELETE FROM tag_usage_probe");
        jdbc.update("DELETE FROM tags");
    }

    @Test
    @DisplayName("태그를 생성·수정·삭제하고 감사 필드를 갱신한다")
    void createPatchAndDeleteFollowApiContractAndAuditFields() throws Exception {
        // given
        String id = create("hash", "해시", 5);

        // when
        mvc
            .perform(admin(patch(ADMIN_PATH + "/" + id)).content("{\"name\":\"그리디\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("태그가 수정되었습니다."))
            .andExpect(jsonPath("$.data.tagId").value(id))
            .andExpect(jsonPath("$.data.name").value("그리디"))
            .andExpect(jsonPath("$.data.displayOrder").value(0));

        // then
        assertThat(jdbc.queryForObject("SELECT display_order FROM tags WHERE id = ?", Integer.class, id)).isZero();

        // when
        mvc
            .perform(admin(patch(ADMIN_PATH + "/" + id)).content("{\"displayOrder\":2}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("그리디"))
            .andExpect(jsonPath("$.data.displayOrder").value(0));

        // then
        assertThat(jdbc.queryForObject("SELECT display_order FROM tags WHERE id = ?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM tags WHERE id = ?", Long.class, id)).isEqualTo(1L);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM tags WHERE created_at IS NOT NULL AND updated_at >= created_at",
                    Integer.class
                )
        ).isEqualTo(1);

        // when & then
        mvc
            .perform(admin(delete(ADMIN_PATH + "/" + id)))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));
    }

    @Test
    @DisplayName("기본 정렬·키워드 검색·페이지 조회와 허용된 정렬을 지원한다")
    void listSupportsDefaultOrderKeywordPagingAndAllowedSort() throws Exception {
        String second = create("stack", "스택/큐", 2);
        String first = create("hash", "해시", 0);
        mvc
            .perform(user(get(LIST_PATH)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].tagId").value(first))
            .andExpect(jsonPath("$.data.content[0].displayOrder").value(0))
            .andExpect(jsonPath("$.data.size").value(100))
            .andExpect(jsonPath("$.data.totalElements").value(2));
        mvc
            .perform(user(get(LIST_PATH).param("size", "1").param("sort", "displayOrder,desc")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].tagId").value(second))
            .andExpect(jsonPath("$.data.content[0].displayOrder").value(1))
            .andExpect(jsonPath("$.data.hasNext").value(true))
            .andExpect(jsonPath("$.data.totalPages").value(2));
        mvc
            .perform(user(get(LIST_PATH).param("keyword", "해시").param("sort", "name,asc")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].name").value("해시"))
            .andExpect(jsonPath("$.data.totalElements").value(1));
        mvc
            .perform(user(get(LIST_PATH).param("page", "1").param("size", "1")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].tagId").value(second));
    }

    @Test
    @DisplayName("사용 중인 순번에 등록하면 뒤 태그를 이동하고 감사 필드를 갱신한다")
    void creatingAtOccupiedOrderShiftsFollowingTagsAndUpdatesAuditFields() throws Exception {
        String first = create("first", "첫 번째", 0);
        String second = create("second", "두 번째", 1);
        String last = create("last", "마지막", 2);
        jdbc.update("UPDATE tags SET updated_at = '2026-01-01 00:00:00' WHERE id = ?", second);

        String inserted = create("inserted", "새 태그", 1);

        assertThat(displayOrder(first)).isZero();
        assertThat(displayOrder(inserted)).isEqualTo(1);
        assertThat(displayOrder(second)).isEqualTo(2);
        assertThat(displayOrder(last)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT version FROM tags WHERE id = ?", Long.class, second)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT version FROM tags WHERE id = ?", Long.class, first)).isZero();
        assertThat(jdbc.queryForObject("SELECT updated_at FROM tags WHERE id = ?", LocalDateTime.class, second))
            .isAfter(LocalDateTime.of(2026, 1, 1, 0, 0));
        mvc
            .perform(user(get(LIST_PATH)))
            .andExpect(jsonPath("$.data.content[1].tagId").value(inserted))
            .andExpect(jsonPath("$.data.content[2].tagId").value(second));
    }

    @Test
    @DisplayName("마지막을 넘는 순번에 등록하면 끝에 추가하고 기존 순번을 유지한다")
    void creatingBeyondLastOrderAppendsWithoutShiftingExistingTags() throws Exception {
        String existing = create("existing", "기존 태그", 5);
        String appended = create("appended", "마지막 태그", 2);
        assertThat(displayOrder(appended)).isEqualTo(1);
        assertThat(displayOrder(existing)).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM tags WHERE id = ?", Long.class, existing)).isZero();
    }

    @Test
    @DisplayName("이름 중복으로 등록이 실패하면 순번 이동을 롤백한다")
    void duplicateNameRollsBackShift() throws Exception {
        String existing = create("existing", "기존 태그", 0);
        mvc
            .perform(
                admin(post(ADMIN_PATH))
                    .header("Idempotency-Key", "duplicate")
                    .content("{\"name\":\"기존 태그\",\"displayOrder\":0}")
            )
            .andExpect(status().isConflict());
        assertThat(displayOrder(existing)).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM tags WHERE id = ?", Long.class, existing)).isZero();
    }

    @Test
    @DisplayName("태그 저장 시 순번 UNIQUE 충돌은 409로 응답하고 선행 순번 변경을 롤백한다")
    void duplicateDisplayOrderReturnsConflictAndRollsBackShift() throws Exception {
        // given
        create("first", "첫 태그", 0);
        create("second", "두 번째 태그", 1);
        var originalTags
            = jdbc
                .queryForList("SELECT id, name, display_order, version, created_at, updated_at FROM tags ORDER BY id");
        TagOrderService orderTarget = AopTestUtils.getUltimateTargetObject(tagOrderService);
        // 실제 순번 이동을 flush한 뒤 점유된 순번을 반환해 DB 저장·예외 변환·롤백 경로를 검증한다.
        doAnswer(invocation -> {
            int availableOrder = (int)invocation.callRealMethod();
            return availableOrder + 1;
        }).when(orderTarget).makeRoomFor(0);

        // when
        var response
            = mvc
                .perform(
                    admin(post(ADMIN_PATH))
                        .header("Idempotency-Key", "order-conflict")
                        .content(mapper.writeValueAsString(new TagCreateRequest("새 태그", 0)))
                )
                .andReturn()
                .getResponse();

        // then
        assertThat(response.getStatus()).isEqualTo(409);
        var error = mapper.readTree(response.getContentAsString()).path("error");
        assertThat(error.path("code").asText()).isEqualTo("PROBLEM_VERSION_CONFLICT");
        assertThat(response.getContentAsString()).doesNotContain("uk_tags_display_order", "Duplicate entry");
        assertThat(
            jdbc.queryForList("SELECT id, name, display_order, version, created_at, updated_at FROM tags ORDER BY id")
        ).isEqualTo(originalTags);
    }

    @ParameterizedTest
    @ValueSource(
        booleans = {
            false,
            true
        }
    )
    @DisplayName("같은 순번에 동시에 등록해도 태그마다 연속된 순번을 배정한다")
    void concurrentCreatesAtSameOrderKeepDistinctPositions(boolean hasExistingTag) throws Exception {
        // given
        if (hasExistingTag) {
            create("existing", "기존 태그", 2);
        }
        // when
        List<String> ids
            = race(
                () -> tagService.create(new TagCreateRequest("새 태그 A", 0)).tagId(),
                () -> tagService.create(new TagCreateRequest("새 태그 B", 0)).tagId()
            );
        // then
        assertThat(ids.get(0)).isNotEqualTo(ids.get(1));
        List<Integer> orders
            = jdbc.queryForList("SELECT display_order FROM tags ORDER BY display_order", Integer.class);
        assertThat(orders).containsExactlyElementsOf(hasExistingTag ? List.of(0, 1, 2) : List.of(0, 1));
    }

    @Test
    @DisplayName("서로 다른 Redis 클라이언트의 태그 등록을 직렬화한다")
    void separateRedisClientsSerializeConcurrentTagCreation() throws Exception {
        // given
        RedissonClient otherClient = Redisson.create(new Config(redisson.getConfig()));
        try {
            TagService otherService
                = new TagService(tags, new TagOrderService(tags, new RedisTransactionLock(otherClient)));
            var transaction = new TransactionTemplate(transactionManager);
            // when
            race(
                () -> tagService.create(new TagCreateRequest("첫 태그", 2)).tagId(),
                () -> transaction.execute(status -> otherService.create(new TagCreateRequest("두 번째 태그", 2)).tagId())
            );
            // then
            assertThat(jdbc.queryForList("SELECT display_order FROM tags ORDER BY display_order", Integer.class))
                .containsExactly(0, 1);
        } finally {
            otherClient.shutdown();
        }
    }

    @ParameterizedTest
    @ValueSource(
        booleans = {
            false,
            true
        }
    )
    @DisplayName("커밋이나 롤백이 끝날 때까지 잠금을 유지한 뒤 해제한다")
    void redisLockIsHeldUntilTransactionCompletesAndReleasedAfterwards(boolean rollback) throws Exception {
        // given
        String first = create("first", "첫 태그", 0);
        String following = create("following", "뒤 태그", 1);
        RedissonClient otherClient = Redisson.create(new Config(redisson.getConfig()));
        var otherLock = otherClient.getLock("problem:tags:order:lock");
        try {
            // when
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                tagService.create(new TagCreateRequest("태그", 1));
                assertThat(displayOrder(first)).isZero();
                assertThat(displayOrder(following)).isEqualTo(2);
                assertThat(otherLock.tryLock()).isFalse();
                if (rollback) {
                    transaction.setRollbackOnly();
                }
            });
            // then
            assertThat(otherLock.tryLock()).isTrue();
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags", Integer.class)).isEqualTo(rollback ? 2 : 3);
            assertThat(displayOrder(following)).isEqualTo(rollback ? 1 : 2);
            assertThat(jdbc.queryForObject("SELECT version FROM tags WHERE id = ?", Long.class, following))
                .isEqualTo(rollback ? 0L : 2L);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags WHERE display_order < 0", Integer.class))
                .isZero();
        } finally {
            if (otherLock.isHeldByCurrentThread()) {
                otherLock.unlock();
            }
            otherClient.shutdown();
        }
    }

    @Test
    @DisplayName("잠금 대기가 초과되면 등록을 거부하고 태그를 저장하지 않는다")
    void busyRedisLockRejectsCreationWithoutPersistingTag() throws Exception {
        RedissonClient otherClient = Redisson.create(new Config(redisson.getConfig()));
        var otherLock = otherClient.getLock("problem:tags:order:lock");
        try {
            otherLock.lock();
            mvc
                .perform(
                    admin(post(ADMIN_PATH))
                        .header("Idempotency-Key", "busy")
                        .content("{\"name\":\"태그\",\"displayOrder\":1}")
                )
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("PROBLEM_VERSION_CONFLICT"));
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags", Integer.class)).isZero();
        } finally {
            otherLock.unlock();
            otherClient.shutdown();
        }
    }

    @Test
    @DisplayName("정수 최댓값 순번 등록을 마지막 위치로 보정한다")
    void maximumRequestedOrderIsClampedToLastOnCreation() throws Exception {
        String existing = create("existing", "기존 태그", Integer.MAX_VALUE);
        mvc
            .perform(
                admin(post(ADMIN_PATH))
                    .header("Idempotency-Key", "overflow")
                    .content(mapper.writeValueAsString(new TagCreateRequest("새 태그", Integer.MAX_VALUE)))
            )
            .andExpect(status().isCreated());
        assertThat(displayOrder(existing)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags", Integer.class)).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(
        booleans = {
            false,
            true
        }
    )
    @DisplayName("기존 순번으로 이동하면 해당 구간의 순번을 조정한다")
    void updatingToOccupiedOrderShiftsOtherTags(boolean moveForward) throws Exception {
        // given
        String first = create("first", "첫 태그", 0);
        String second = create("second", "두 번째 태그", 1);
        String third = create("third", "세 번째 태그", 2);
        String moving = moveForward ? first : third;
        // when
        mvc
            .perform(
                admin(patch(ADMIN_PATH + "/" + moving))
                    .content(mapper.writeValueAsString(new TagUpdateRequest(null, moveForward ? 2 : 0)))
            )
            .andExpect(status().isOk());
        // then
        assertThat(displayOrder(moving)).isEqualTo(moveForward ? 2 : 0);
        assertThat(displayOrder(second)).isEqualTo(moveForward ? 0 : 2);
        assertThat(displayOrder(moveForward ? third : first)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM tags WHERE id = ?", Long.class, moving)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT version FROM tags WHERE id = ?", Long.class, second)).isEqualTo(2L);
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "{\"displayOrder\":0}",
            "{\"name\":\"새 이름\"}"
        }
    )
    @DisplayName("이름만 수정하거나 기존 순번을 유지하면 다른 태그 순번을 바꾸지 않는다")
    void unchangedOrderDoesNotShiftOtherTags(String body) throws Exception {
        String first = create("first", "첫 태그", 0);
        String second = create("second", "두 번째 태그", 1);
        mvc.perform(admin(patch(ADMIN_PATH + "/" + first)).content(body)).andExpect(status().isOk());
        assertThat(displayOrder(first)).isZero();
        assertThat(displayOrder(second)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM tags WHERE id = ?", Long.class, second)).isZero();
    }

    @Test
    @DisplayName("마지막을 넘는 순번으로 수정하면 끝으로 이동하고 기존 빈 순번을 메운다")
    void updatingBeyondLastOrderMovesToEndAndClosesOldGap() throws Exception {
        String moving = create("moving", "이동할 태그", 1);
        String following = create("following", "뒤 태그", 5);
        mvc.perform(admin(patch(ADMIN_PATH + "/" + moving)).content("{\"displayOrder\":3}")).andExpect(status().isOk());
        assertThat(displayOrder(moving)).isEqualTo(1);
        assertThat(displayOrder(following)).isZero();
    }

    @Test
    @DisplayName("정수 최솟값 순번 수정은 첫 위치로 보정한다")
    void minimumRequestedOrderMovesTagToFront() throws Exception {
        String existing = create("existing", "기존 태그", 2);
        String moving = create("moving", "이동할 태그", Integer.MAX_VALUE);
        mvc
            .perform(admin(patch(ADMIN_PATH + "/" + moving)).content("{\"displayOrder\":-2147483648}"))
            .andExpect(status().isOk());
        assertThat(displayOrder(moving)).isZero();
        assertThat(displayOrder(existing)).isEqualTo(1);
    }

    @Test
    @DisplayName("이름 중복 수정이 실패하면 다른 태그의 순번 이동을 롤백한다")
    void duplicateNameRollsBackUpdateAndShift() throws Exception {
        String moving = create("moving", "이동할 태그", 1);
        String existing = create("existing", "기존 태그", 2);
        mvc
            .perform(
                admin(patch(ADMIN_PATH + "/" + moving))
                    .content(mapper.writeValueAsString(new TagUpdateRequest("기존 태그", 2)))
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("PROBLEM_TAG_DUPLICATED"));
        assertThat(displayOrder(moving)).isZero();
        assertThat(displayOrder(existing)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM tags WHERE id = ?", Long.class, existing)).isZero();
        assertThat(jdbc.queryForObject("SELECT name FROM tags WHERE id = ?", String.class, moving)).isEqualTo("이동할 태그");
    }

    @Test
    @DisplayName("정수 최댓값 순번 수정은 마지막 위치로 보정한다")
    void maximumRequestedOrderMovesTagToEnd() throws Exception {
        String moving = create("moving", "이동할 태그", 1);
        String existing = create("existing", "기존 태그", Integer.MAX_VALUE);
        mvc
            .perform(
                admin(patch(ADMIN_PATH + "/" + moving))
                    .content(mapper.writeValueAsString(new TagUpdateRequest(null, Integer.MAX_VALUE)))
            )
            .andExpect(status().isOk());
        assertThat(displayOrder(moving)).isEqualTo(1);
        assertThat(displayOrder(existing)).isZero();
    }

    @Test
    @DisplayName("서로 다른 태그를 같은 순번으로 동시에 수정해도 순번을 직렬화한다")
    void concurrentUpdatesToSameOrderKeepDistinctPositions() throws Exception {
        // given
        String first = create("first", "첫 태그", 1);
        String second = create("second", "두 번째 태그", 2);
        create("existing", "기존 태그", 3);
        // when
        race(
            () -> tagService.update(Long.parseLong(first), new TagUpdateRequest(null, 3)).tagId(),
            () -> tagService.update(Long.parseLong(second), new TagUpdateRequest(null, 3)).tagId()
        );
        // then
        assertThat(jdbc.queryForList("SELECT display_order FROM tags ORDER BY display_order", Integer.class))
            .containsExactly(0, 1, 2);
    }

    @Test
    @DisplayName("중간 태그 삭제 후 뒤 순번만 당기고 감사 필드를 갱신한다")
    void deletingTagShiftsOnlyFollowingOrdersAndUpdatesAuditFields() throws Exception {
        String first = create("first", "첫 태그", 1);
        String deleted = create("deleted", "삭제할 태그", 2);
        String third = create("third", "세 번째 태그", 3);
        String last = create("last", "마지막 태그", 5);
        jdbc.update("UPDATE tags SET updated_at = '2026-01-01 00:00:00' WHERE id = ?", third);
        mvc
            .perform(admin(delete(ADMIN_PATH + "/" + deleted)))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));
        assertThat(displayOrder(first)).isZero();
        assertThat(displayOrder(third)).isEqualTo(1);
        assertThat(displayOrder(last)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags WHERE id = ?", Integer.class, deleted)).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM tags WHERE id = ?", Long.class, first)).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM tags WHERE id = ?", Long.class, third)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM tags WHERE id = ?", LocalDateTime.class, third))
            .isAfter(LocalDateTime.of(2026, 1, 1, 0, 0));
    }

    @ParameterizedTest
    @ValueSource(
        booleans = {
            false,
            true
        }
    )
    @DisplayName("첫 태그 삭제는 뒤 순번을 당기고 마지막 태그 삭제는 앞 순번을 유지한다")
    void deletingFirstOrLastTagHandlesOrderBoundaries(boolean deleteFirst) throws Exception {
        String first = create("first", "첫 태그", 1);
        String last = create("last", "마지막 태그", Integer.MAX_VALUE);
        mvc.perform(admin(delete(ADMIN_PATH + "/" + (deleteFirst ? first : last)))).andExpect(status().isNoContent());
        assertThat(displayOrder(deleteFirst ? last : first)).isZero();
    }

    @Test
    @DisplayName("서로 다른 태그를 동시에 삭제해도 남은 순번을 연속되게 유지한다")
    void concurrentDeletesShiftRemainingTagTwice() throws Exception {
        // given
        String first = create("first", "첫 태그", 1);
        String second = create("second", "두 번째 태그", 2);
        String remaining = create("remaining", "남을 태그", 3);
        // when
        race(() -> {
            tagService.delete(Long.parseLong(first));
            return first;
        }, () -> {
            tagService.delete(Long.parseLong(second));
            return second;
        });
        // then
        assertThat(displayOrder(remaining)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags", Integer.class)).isEqualTo(1);
    }

    /**
     * DB에서 태그의 표시 순번을 조회한다.
     *
     * @param id 조회할 태그 ID
     * @return 현재 저장된 표시 순번
     */
    private int displayOrder(String id) {
        return jdbc.queryForObject("SELECT display_order FROM tags WHERE id = ?", Integer.class, id);
    }

    @Test
    @DisplayName("데이터베이스 제약은 중복 표시 순번 저장을 거부한다")
    void databaseRejectsDuplicateDisplayOrders() throws Exception {
        create("first", "첫 태그", 0);
        String second = create("second", "두 번째 태그", 1);

        assertThatThrownBy(() -> jdbc.update("UPDATE tags SET display_order = 0 WHERE id = ?", second))
            .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("""
            INSERT INTO tags (name, display_order, created_at, updated_at, version)
            VALUES ('중복 순번', 0, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6), 0)
            """)).isInstanceOf(DataIntegrityViolationException.class);

        assertThat(jdbc.queryForList("SELECT display_order FROM tags ORDER BY display_order", Integer.class))
            .containsExactly(0, 1);
    }

    @ParameterizedTest
    @ValueSource(
        ints = {
            Integer.MIN_VALUE,
            -1,
            0,
            1,
            Integer.MAX_VALUE
        }
    )
    @DisplayName("첫 태그는 요청 순번과 관계없이 0번에 등록한다")
    void firstTagAlwaysStartsAtZero(int requestedOrder) throws Exception {
        String id = create("first", "첫 태그", requestedOrder);
        assertThat(displayOrder(id)).isZero();
    }

    @ParameterizedTest
    @CsvSource(
        {
            "-2147483648, 0",
            "-1, 0",
            "0, 0",
            "1, 1",
            "2, 2",
            "3, 3",
            "2147483647, 3"
        }
    )
    @DisplayName("생성 순번을 범위 안으로 보정하고 기존 태그 순서를 유지한다")
    void creationClampsOrderAndKeepsConsecutivePositions(int requestedOrder, int expectedOrder) throws Exception {
        // given
        String first = create("first", "첫 태그", 0);
        String second = create("second", "두 번째 태그", 1);
        String third = create("third", "세 번째 태그", 2);

        // when
        String response
            = mvc
                .perform(
                    admin(post(ADMIN_PATH))
                        .header("Idempotency-Key", "inserted")
                        .content(mapper.writeValueAsString(new TagCreateRequest("새 태그", requestedOrder)))
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.displayOrder").value(expectedOrder))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String inserted = mapper.readTree(response).path("data").path("tagId").asText();

        // then
        assertThat(displayOrder(inserted)).isEqualTo(expectedOrder);
        assertThat(jdbc.queryForList("SELECT display_order FROM tags ORDER BY display_order", Integer.class))
            .containsExactly(0, 1, 2, 3);
        assertThat(
            jdbc.queryForList("SELECT id FROM tags WHERE id <> ? ORDER BY display_order", String.class, inserted)
        ).containsExactly(first, second, third);
    }

    @ParameterizedTest
    @CsvSource(
        {
            "-2147483648, 0",
            "-1, 0",
            "0, 0",
            "1, 1",
            "2, 2",
            "3, 2",
            "2147483647, 2"
        }
    )
    @DisplayName("수정 순번을 범위 안으로 보정하고 다른 태그 순서를 유지한다")
    void updateClampsOrderAndKeepsConsecutivePositions(int requestedOrder, int expectedOrder) throws Exception {
        // given
        String first = create("first", "첫 태그", 0);
        String moving = create("moving", "이동할 태그", 1);
        String last = create("last", "마지막 태그", 2);

        // when
        mvc
            .perform(
                admin(patch(ADMIN_PATH + "/" + moving))
                    .content(mapper.writeValueAsString(new TagUpdateRequest(null, requestedOrder)))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.displayOrder").value(expectedOrder));

        // then
        assertThat(displayOrder(moving)).isEqualTo(expectedOrder);
        assertThat(jdbc.queryForList("SELECT display_order FROM tags ORDER BY display_order", Integer.class))
            .containsExactly(0, 1, 2);
        assertThat(jdbc.queryForList("SELECT id FROM tags WHERE id <> ? ORDER BY display_order", String.class, moving))
            .containsExactly(first, last);
    }

    @Test
    @DisplayName("검색어의 SQL 와일드카드 문자를 일반 문자로 검색한다")
    void keywordTreatsSqlWildcardsAsLiteralText() throws Exception {
        create("percent", "100%", 1);
        create("other", "100개", 1);
        mvc
            .perform(user(get(LIST_PATH).param("keyword", "%")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].name").value("100%"));
    }

    @Test
    @DisplayName("허용하지 않은 페이지·크기·정렬 조건을 입력 오류로 거부한다")
    void invalidListParametersAreRejected() throws Exception {
        for (String sort : List.of("id,asc", "name,invalid", "name,asc,extra", "name")) {
            mvc
                .perform(user(get(LIST_PATH).param("sort", sort)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
        }
        mvc
            .perform(user(get(LIST_PATH).param("keyword", "a".repeat(201))))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
        for (String page : List.of("-1", "21474837", "2147483647")) {
            mvc
                .perform(user(get(LIST_PATH).param("page", page).param("size", "100")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
        }
        mvc.perform(user(get(LIST_PATH).param("size", "101"))).andExpect(status().isBadRequest());
        mvc.perform(user(get(LIST_PATH).param("size", "0"))).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("최대 페이지와 페이지 크기로 조회해도 정상 응답을 반환한다")
    void maximumPageNumberIsAccepted() throws Exception {
        mvc
            .perform(user(get(LIST_PATH).param("page", "21474836").param("size", "100")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isEmpty())
            .andExpect(jsonPath("$.data.page").value(21_474_836));
    }

    @Test
    @DisplayName("인증 사용자는 조회하고 관리자만 태그를 변경할 수 있다")
    void onlyAuthenticatedUsersCanReadAndOnlyAdminsCanWrite() throws Exception {
        mvc.perform(get(LIST_PATH)).andExpect(status().isUnauthorized());
        mvc.perform(user(get(LIST_PATH))).andExpect(status().isOk());
        mvc
            .perform(
                user(post(ADMIN_PATH)).header("Idempotency-Key", "key").content("{\"name\":\"태그\",\"displayOrder\":0}")
            )
            .andExpect(status().isForbidden());
        mvc.perform(user(patch(ADMIN_PATH + "/1")).content("{\"name\":\"태그\"}")).andExpect(status().isForbidden());
        mvc.perform(user(delete(ADMIN_PATH + "/1"))).andExpect(status().isForbidden());
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "{}",
            "{\"name\":\"\",\"displayOrder\":0}",
            "{\"name\":\"  \",\"displayOrder\":0}",
            "{\"name\":\"태그\"}"
        }
    )
    @DisplayName("잘못된 생성 요청은 입력 오류로 거부하고 태그를 저장하지 않는다")
    void invalidCreateDoesNotPersistTag(String body) throws Exception {
        mvc
            .perform(admin(post(ADMIN_PATH)).header("Idempotency-Key", "invalid").content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "",
            "   ",
            "\t\n",
            "\u2003"
        }
    )
    @DisplayName("공백 이름 수정은 필드 검증 오류로 거부하고 태그를 보존한다")
    void blankPatchNameReturnsFieldValidationError(String name) throws Exception {
        String id = create("tag", "해시", 0);
        create("following", "다음 태그", 1);
        mvc
            .perform(
                admin(patch(ADMIN_PATH + "/" + id)).content(mapper.writeValueAsString(new TagUpdateRequest(name, 1)))
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"))
            .andExpect(jsonPath("$.error.details[0].field").value("name"));
        assertThat(jdbc.queryForObject("SELECT name FROM tags WHERE id = ?", String.class, id)).isEqualTo("해시");
        assertThat(displayOrder(id)).isZero();
    }

    @ParameterizedTest
    @ValueSource(
        strings = {
            "  새 이름  ",
            "\u2003새 이름\u2003"
        }
    )
    @DisplayName("유효한 수정 이름의 앞뒤 공백을 제거해 저장한다")
    void patchStripsSurroundingWhitespace(String name) throws Exception {
        String id = create("tag", "해시", 0);
        mvc
            .perform(
                admin(patch(ADMIN_PATH + "/" + id)).content(mapper.writeValueAsString(new TagUpdateRequest(name, null)))
            )
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("새 이름"));
        assertThat(jdbc.queryForObject("SELECT name FROM tags WHERE id = ?", String.class, id)).isEqualTo("새 이름");
    }

    @Test
    @DisplayName("빈 수정 요청·공백 이름·없는 태그를 각각 해당 오류로 거부한다")
    void emptyOrBlankPatchAndUnknownTagsReturnExpectedErrors() throws Exception {
        String id = create("tag", "해시", 0);
        for (String body : List.of("{}", "{\"name\":\"  \"}", "{\"name\":null,\"displayOrder\":null}")) {
            mvc
                .perform(admin(patch(ADMIN_PATH + "/" + id)).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
            assertThat(jdbc.queryForObject("SELECT name FROM tags WHERE id = ?", String.class, id)).isEqualTo("해시");
        }
        mvc
            .perform(admin(patch(ADMIN_PATH + "/999999")).content("{\"name\":\"해시\"}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("PROBLEM_TAG_NOT_FOUND"));
        mvc
            .perform(admin(delete(ADMIN_PATH + "/999999")))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("PROBLEM_TAG_NOT_FOUND"));
    }

    @Test
    @DisplayName("중복 이름 생성·수정은 실패하고 기존 값을 보존한다")
    void duplicateNamesRollbackCreationAndPartialUpdate() throws Exception {
        create("first", "해시", 0);
        mvc
            .perform(
                admin(post(ADMIN_PATH))
                    .header("Idempotency-Key", "failed")
                    .content("{\"name\":\"해시\",\"displayOrder\":1}")
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("PROBLEM_TAG_DUPLICATED"));
        String id = create("failed", "스택", 2);
        mvc
            .perform(admin(patch(ADMIN_PATH + "/" + id)).content("{\"name\":\"해시\",\"displayOrder\":9}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("PROBLEM_TAG_DUPLICATED"));
        assertThat(jdbc.queryForObject("SELECT name FROM tags WHERE id = ?", String.class, id)).isEqualTo("스택");
        assertThat(jdbc.queryForObject("SELECT display_order FROM tags WHERE id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("참조 중 태그 삭제는 거부하고 이름 변경은 허용한다")
    void referencedTagCannotBeDeletedButCanBeRenamed() throws Exception {
        String id = create("used", "해시", 0);
        String following = create("following", "뒤 태그", 1);
        jdbc.update("INSERT INTO tag_usage_probe (id, tag_id) VALUES (1, ?)", id);
        mvc
            .perform(admin(delete(ADMIN_PATH + "/" + id)))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("PROBLEM_TAG_IN_USE"));
        assertThat(displayOrder(following)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM tags WHERE id = ?", Long.class, following)).isZero();
        mvc.perform(admin(patch(ADMIN_PATH + "/" + id)).content("{\"name\":\"이름 변경\"}")).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_usage_probe", Integer.class)).isEqualTo(1);
        jdbc.update("DELETE FROM tag_usage_probe");
        mvc.perform(admin(delete(ADMIN_PATH + "/" + id))).andExpect(status().isNoContent());
        assertThat(displayOrder(following)).isZero();
    }

    @Test
    @DisplayName("같은 이름의 동시 등록은 하나만 성공하고 나머지는 중복 오류를 반환한다")
    void concurrentDuplicateNamesCreateOneTagAndRejectTheOtherRequest() throws Exception {
        List<String> outcomes = race(() -> createOutcome("first"), () -> createOutcome("second"));
        assertThat(outcomes).containsExactlyInAnyOrder("CREATED", "PROBLEM_TAG_DUPLICATED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags", Integer.class)).isEqualTo(1);
    }

    /**
     * 태그 생성 API의 응답을 동시 요청 결과 비교용 문자열로 변환한다.
     *
     * @param key 태그 생성에 사용할 멱등키
     * @return 성공 시 {@code CREATED}, 실패 시 API 응답의 오류 코드 이름
     * @throws Exception API 요청 또는 응답 파싱 중 오류가 발생한 경우
     */
    private String createOutcome(String key) throws Exception {
        var response
            = mvc
                .perform(
                    admin(post(ADMIN_PATH))
                        .header("Idempotency-Key", key)
                        .content(mapper.writeValueAsString(new TagCreateRequest("해시", 0)))
                )
                .andReturn()
                .getResponse();
        if (response.getStatus() == 201) {
            return "CREATED";
        }
        return mapper.readTree(response.getContentAsString()).path("error").path("code").asText();
    }

    /**
     * 두 작업의 시작을 맞추어 동시에 실행하고 결과를 모은다.
     *
     * @param first 첫 번째 동시 작업
     * @param second 두 번째 동시 작업
     * @return 전달한 작업 순서대로 모은 실행 결과
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    private List<String> race(Callable<String> first, Callable<String> second) throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var futures = List.of(first, second).stream().map(task -> executor.submit(() -> {
                ready.countDown();
                if (!ready.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Concurrent request did not start");
                }
                return task.call();
            })).toList();
            return List.of(futures.get(0).get(15, TimeUnit.SECONDS), futures.get(1).get(15, TimeUnit.SECONDS));
        }
    }

    /**
     * 관리자 API로 태그를 생성하고 응답에서 ID를 추출한다.
     *
     * @param key 요청에 사용할 멱등키
     * @param name 등록할 태그 이름
     * @param displayOrder 등록할 표시 순번
     * @return 생성 응답의 태그 ID
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    private String create(String key, String name, int displayOrder) throws Exception {
        String response
            = mvc
                .perform(
                    admin(post(ADMIN_PATH))
                        .header("Idempotency-Key", key)
                        .content(mapper.writeValueAsString(new TagCreateRequest(name, displayOrder)))
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.message").value("태그가 생성되었습니다."))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var data = mapper.readTree(response).path("data");
        String tagId = data.path("tagId").asText();
        assertThat(data.path("displayOrder").isIntegralNumber()).isTrue();
        return tagId;
    }

    /**
     * 관리자 인증 헤더와 JSON 형식을 테스트 요청에 설정한다.
     *
     * @param request 헤더를 추가할 테스트 요청
     * @return 헤더와 콘텐츠 타입이 설정된 요청 빌더
     */
    private MockHttpServletRequestBuilder admin(MockHttpServletRequestBuilder request) {
        return request
            .header("X-User-Id", "admin")
            .header("X-User-Role", "ADMIN")
            .contentType(MediaType.APPLICATION_JSON);
    }

    /**
     * 일반 사용자 인증 헤더와 JSON 형식을 테스트 요청에 설정한다.
     *
     * @param request 헤더를 추가할 테스트 요청
     * @return 헤더와 콘텐츠 타입이 설정된 요청 빌더
     */
    private MockHttpServletRequestBuilder user(MockHttpServletRequestBuilder request) {
        return request
            .header("X-User-Id", "member")
            .header("X-User-Role", "USER")
            .contentType(MediaType.APPLICATION_JSON);
    }
}
