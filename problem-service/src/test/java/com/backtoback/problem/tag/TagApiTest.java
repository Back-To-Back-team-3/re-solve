package com.backtoback.problem.tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.global.exception.ProblemExceptionHandler;
import com.backtoback.problem.global.lock.RedisTransactionLock;
import com.backtoback.problem.tag.dto.request.TagCreateRequest;
import com.backtoback.problem.tag.dto.request.TagUpdateRequest;
import com.backtoback.problem.tag.repository.TagRepository;
import com.backtoback.problem.tag.scheduler.TagCreationRecordCleanupScheduler;
import com.backtoback.problem.tag.service.TagCreationRecordService;
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

    // 잠금 대기 관측과 테스트 DDL에서만 컨테이너 root 계정을 사용한다. 서비스 연결은 기존 테스트 계정을 유지한다.
    static final MySQLContainer MYSQL
        = new MySQLContainer("mysql:8.4").withDatabaseName("problem_db").withEnv("MYSQL_ROOT_HOST", "%");

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

    @MockitoBean
    private Clock clock;
    @Autowired
    private TagCreationRecordService creationRecords;
    @Autowired
    private TagCreationRecordCleanupScheduler cleanupScheduler;
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
     * 테스트 시계를 초기화하고 이전 테스트의 태그와 요청 기록을 정리한다.
     */
    @BeforeEach
    void clearData() {
        when(clock.getZone()).thenReturn(ZoneId.of("Asia/Seoul"));
        when(clock.instant()).thenReturn(Instant.parse("2026-10-13T00:00:00Z"));
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
        jdbc.update("DELETE FROM lock_transaction_probe");
        jdbc.update("DELETE FROM tag_creation_records");
    }

    @Test
    @DisplayName("태그를 생성·수정·삭제하고 감사 필드를 갱신한다")
    void createPatchAndDeleteFollowApiContractAndAuditFields() throws Exception {
        // given
        String id = create("hash", "해시", 5);
        LocalDateTime createdAt = LocalDateTime.of(2026, 10, 13, 9, 0);
        // JPA 감사 시각과 직접 SQL로 등록한 멱등 기록이 같은 공통 Clock을 사용한다.
        assertThat(jdbc.queryForObject("SELECT created_at FROM tags WHERE id = ?", LocalDateTime.class, id))
            .isEqualTo(createdAt);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT updated_at FROM tag_creation_records WHERE idempotency_key = ?",
                    LocalDateTime.class,
                    "hash"
                )
        ).isEqualTo(createdAt);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT expires_at FROM tag_creation_records WHERE idempotency_key = ?",
                    LocalDateTime.class,
                    "hash"
                )
        ).isEqualTo(createdAt.plusHours(24));
        when(clock.instant()).thenReturn(Instant.parse("2026-10-13T00:01:00Z"));

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
        // 수정해도 생성 시각은 유지하고, 수정 시각만 변경된 Clock을 따른다.
        assertThat(jdbc.queryForObject("SELECT created_at FROM tags WHERE id = ?", LocalDateTime.class, id))
            .isEqualTo(createdAt);
        assertThat(jdbc.queryForObject("SELECT updated_at FROM tags WHERE id = ?", LocalDateTime.class, id))
            .isEqualTo(createdAt.plusMinutes(1));
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

    /**
     * 동일 생성 요청을 재전송해도 순번 이동이 반복되지 않는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void replayDoesNotShiftOrdersAgain() throws Exception {
        String existing = create("existing", "기존 태그", 0);
        String inserted = create("inserted", "새 태그", 0);
        assertThat(create("inserted", "새 태그", 0)).isEqualTo(inserted);
        assertThat(displayOrder(existing)).isEqualTo(1);
        assertThat(displayOrder(inserted)).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM tags WHERE id = ?", Long.class, existing)).isEqualTo(2L);
    }

    @Test
    @DisplayName("이름 중복으로 등록이 실패하면 순번 이동을 롤백한다")
    void duplicateNameRollsBackShiftAndRequestRecord() throws Exception {
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
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_creation_records", Integer.class)).isEqualTo(1);
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
                () -> tagService.create("admin", "first", new TagCreateRequest("새 태그 A", 0)).tagId(),
                () -> tagService.create("admin", "second", new TagCreateRequest("새 태그 B", 0)).tagId()
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
                = new TagService(
                    tags,
                    creationRecords,
                    new TagOrderService(tags, new RedisTransactionLock(otherClient))
                );
            var transaction = new TransactionTemplate(transactionManager);
            // when
            race(
                () -> tagService.create("admin", "first", new TagCreateRequest("첫 태그", 2)).tagId(),
                () -> transaction
                    .execute(
                        status -> otherService.create("admin", "second", new TagCreateRequest("두 번째 태그", 2)).tagId()
                    )
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
                tagService.create("admin", "transaction", new TagCreateRequest("태그", 1));
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
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_creation_records", Integer.class))
                .isEqualTo(rollback ? 2 : 3);
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
    void busyRedisLockRejectsCreationWithoutPersistingRequestOrTag() throws Exception {
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
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_creation_records", Integer.class)).isZero();
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
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_creation_records", Integer.class)).isEqualTo(2);
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
     * 실제 Redis 검사 콜백이 성공한 뒤 첫 트랜잭션을 멈춘다.
     * 잠금을 새로 획득한 경쟁 요청도 DB 행 잠금을 넘지 못하며, 검사 후 소유권을 잃은 첫 요청은 커밋할 수 있다.
     */
    @ParameterizedTest
    @MethodSource("commitGapScenarios")
    @Timeout(30)
    @DisplayName("소유권 확인 후 잠금을 잃어도 생성·이동·삭제의 DB 경합과 롤백이 순번을 보호한다")
    void databaseLocksProtectOrdersAfterSuccessfulRedisCheck(Isolation isolation, String operation) throws Exception {
        // given
        String last = null;
        if (!"EMPTY_CREATE".equals(operation)) {
            create("first", "첫 태그", 0);
            create("middle", "중간 태그", 1);
            last = create("last", "끝 태그", 2);
        }
        String lastId = last;
        CountDownLatch checked = new CountDownLatch(1);
        CountDownLatch resumeCommit = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> inTransaction(isolation, () -> {
                switch (operation) {
                    case "CREATE", "EMPTY_CREATE" ->
                        tagService.create("admin", "commit-gap-first", new TagCreateRequest("첫 요청 태그", 0));
                    case "MOVE" -> tagService.update(Long.parseLong(lastId), new TagUpdateRequest(null, 0));
                    case "DELETE" -> tagService.delete(Long.parseLong(lastId));
                    default -> throw new IllegalArgumentException(operation);
                }
                tags.flush();
                pauseAfterRedisCheck(checked, resumeCommit);
                return "COMMITTED";
            }));
            try {
                // when: 실제 소유권 검사 뒤, DB 커밋 전에 다른 소유자가 진입한다.
                awaitSignal(checked);
                assertThat(redisson.getLock("problem:tags:order:lock").forceUnlock()).isTrue();
                var competing = executor.submit(() -> captureFailure(() -> inTransaction(isolation, () -> {
                    jdbc.update("INSERT INTO lock_transaction_probe (id, payload) VALUES (1, 'competing-write')");
                    tagService.create("admin", "commit-gap-competing", new TagCreateRequest("경쟁 요청 태그", 0));
                    return null;
                })));

                // then: Redis 획득 실패가 아니라 실제 DB 잠금 대기 실패여야 한다.
                RuntimeException failure = competing.get(10, TimeUnit.SECONDS);
                assertThat(failure).isInstanceOf(PessimisticLockingFailureException.class);
                assertThat(hasMysqlError(failure, 1205)).as("MySQL 행 잠금 대기 시간 초과").isTrue();
                assertVersionConflict(failure);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lock_transaction_probe", Integer.class)).isZero();
            } finally {
                resumeCommit.countDown();
            }
            assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo("COMMITTED");
        } finally {
            resumeCommit.countDown();
        }
        switch (operation) {
            case "CREATE" -> assertTagSequence("첫 요청 태그", "첫 태그", "중간 태그", "끝 태그");
            case "EMPTY_CREATE" -> assertTagSequence("첫 요청 태그");
            case "MOVE" -> assertTagSequence("끝 태그", "첫 태그", "중간 태그");
            case "DELETE" -> assertTagSequence("첫 태그", "중간 태그");
            default -> throw new IllegalArgumentException(operation);
        }
    }

    static Stream<Arguments> commitGapScenarios() {
        return Stream
            .of(Isolation.REPEATABLE_READ, Isolation.READ_COMMITTED)
            .flatMap(
                isolation -> Stream
                    .of("CREATE", "EMPTY_CREATE", "MOVE", "DELETE")
                    .map(operation -> Arguments.of(isolation, operation))
            );
    }

    /** 먼저 읽은 엔티티의 버전이 뒤처진 경우, 태그 변경과 같은 트랜잭션의 선행 쓰기도 롤백되는지 확인한다. */
    @ParameterizedTest
    @EnumSource(
        value = Isolation.class,
        names = {
            "REPEATABLE_READ",
            "READ_COMMITTED"
        }
    )
    @Timeout(30)
    @DisplayName("오래된 태그 수정은 버전 충돌로 거부하고 같은 트랜잭션의 선행 쓰기를 롤백한다")
    void staleTagVersionRollsBackEarlierWrite(Isolation isolation) throws Exception {
        // given
        String first = create("first", "첫 태그", 0);
        create("last", "끝 태그", 1);
        CountDownLatch loaded = new CountDownLatch(1);
        CountDownLatch updated = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var stale = executor.submit(() -> captureFailure(() -> inTransaction(isolation, () -> {
                assertThat(tags.findById(Long.parseLong(first)).orElseThrow().getVersion()).isZero();
                jdbc.update("INSERT INTO lock_transaction_probe (id, payload) VALUES (1, 'stale-write')");
                loaded.countDown();
                awaitSignal(updated);
                tagService.update(Long.parseLong(first), new TagUpdateRequest("오래된 수정", null));
                tags.flush();
                return null;
            })));
            try {
                // when: 다른 트랜잭션의 커밋 후에도 첫 영속성 컨텍스트는 이전 버전을 보유한다.
                awaitSignal(loaded);
                inTransaction(
                    isolation,
                    () -> tagService.update(Long.parseLong(first), new TagUpdateRequest("최신 태그", null))
                );
            } finally {
                updated.countDown();
            }
            // then
            RuntimeException failure = stale.get(10, TimeUnit.SECONDS);
            assertThat(failure).isInstanceOf(ObjectOptimisticLockingFailureException.class);
            assertVersionConflict(failure);
        } finally {
            updated.countDown();
        }
        assertTagSequence("최신 태그", "끝 태그");
        assertThat(jdbc.queryForObject("SELECT version FROM tags WHERE id = ?", Long.class, first)).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lock_transaction_probe", Integer.class)).isZero();
    }

    /** 대상 삭제 후 전체 행 잠금으로 확장하는 기존 순서에서 교착을 만들고 실패 트랜잭션의 삭제·쓰기를 확인한다. */
    @ParameterizedTest
    @EnumSource(
        value = Isolation.class,
        names = {
            "REPEATABLE_READ",
            "READ_COMMITTED"
        }
    )
    @Timeout(30)
    @DisplayName("두 삭제의 교착은 409 충돌로 변환되고 실패한 삭제와 선행 쓰기는 롤백된다")
    void concurrentDeleteDeadlockRollsBackFailedChanges(Isolation isolation) throws Exception {
        // given
        String firstId = create("first", "첫 태그", 0);
        create("middle", "중간 태그", 1);
        String lastId = create("last", "끝 태그", 2);
        var originalTags = jdbc.queryForList("SELECT * FROM tags ORDER BY id");
        CountDownLatch firstDeleted = new CountDownLatch(1);
        CountDownLatch bothDeleted = new CountDownLatch(2);
        TagOrderService orderTarget = AopTestUtils.getUltimateTargetObject(tagOrderService);
        doAnswer(invocation -> {
            firstDeleted.countDown();
            bothDeleted.countDown();
            awaitSignal(bothDeleted);
            return invocation.callRealMethod();
        }).when(orderTarget).closeGap();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> captureFailure(() -> inTransaction(isolation, () -> {
                jdbc.update("INSERT INTO lock_transaction_probe (id, payload) VALUES (1, 'first-delete')");
                tagService.delete(Long.parseLong(firstId));
                return null;
            })));
            try {
                // when: 두 요청이 서로 다른 행을 삭제한 뒤 전체 행 잠금을 요구하도록 맞춘다.
                awaitSignal(firstDeleted);
                assertThat(redisson.getLock("problem:tags:order:lock").forceUnlock()).isTrue();
                var second = executor.submit(() -> captureFailure(() -> inTransaction(isolation, () -> {
                    jdbc.update("INSERT INTO lock_transaction_probe (id, payload) VALUES (2, 'second-delete')");
                    tagService.delete(Long.parseLong(lastId));
                    return null;
                })));
                RuntimeException firstFailure = first.get(10, TimeUnit.SECONDS);
                RuntimeException secondFailure = second.get(10, TimeUnit.SECONDS);

                // then: 교착의 생존자가 첫 요청이면 Redis 유실 검사로도 롤백될 수 있다.
                assertVersionConflict(firstFailure);
                assertThat(hasMysqlError(firstFailure, 1213) || hasMysqlError(secondFailure, 1213))
                    .as("MySQL이 감지한 교착의 실패 요청")
                    .isTrue();
                if (secondFailure != null) {
                    assertVersionConflict(secondFailure);
                }
                assertThat(
                    jdbc.queryForObject("SELECT COUNT(*) FROM lock_transaction_probe WHERE id = 1", Integer.class)
                ).isZero();
                assertThat(
                    jdbc.queryForObject("SELECT COUNT(*) FROM lock_transaction_probe WHERE id = 2", Integer.class)
                ).isEqualTo(secondFailure == null ? 1 : 0);
                assertThat(jdbc.queryForList("SELECT * FROM tags ORDER BY id"))
                    .isEqualTo(secondFailure == null ? originalTags.subList(0, 2) : originalTags);
                if (secondFailure == null) {
                    assertTagSequence("첫 태그", "중간 태그");
                } else {
                    assertTagSequence("첫 태그", "중간 태그", "끝 태그");
                }
            } finally {
                // 준비 중 실패해도 첫 작업이 신호를 기다리며 남지 않게 한다.
                bothDeleted.countDown();
                bothDeleted.countDown();
            }
        }
    }

    /** 테스트 트랜잭션에만 격리 수준·잠금 대기 제한을 적용하고 풀에 반환하기 전 세션 설정을 복원한다. */
    private <T> T inTransaction(Isolation isolation, Supplier<T> operation) {
        var transaction = new TransactionTemplate(transactionManager);
        transaction.setIsolationLevel(isolation.value());
        return transaction.execute(status -> {
            assertThat(jdbc.queryForObject("SELECT @@session.transaction_isolation", String.class))
                .isEqualTo(isolation.name().replace('_', '-'));
            int previousTimeout = jdbc.queryForObject("SELECT @@session.innodb_lock_wait_timeout", Integer.class);
            jdbc.execute("SET SESSION innodb_lock_wait_timeout = 1");
            Throwable operationFailure = null;
            try {
                return operation.get();
            } catch (RuntimeException | Error failure) {
                operationFailure = failure;
                throw failure;
            } finally {
                try {
                    jdbc.execute("SET SESSION innodb_lock_wait_timeout = " + previousTimeout);
                } catch (RuntimeException restoreFailure) {
                    if (operationFailure != null) {
                        operationFailure.addSuppressed(restoreFailure);
                    } else {
                        throw restoreFailure;
                    }
                }
            }
        });
    }

    /** 실제 잠금 검사와 같은 순서값으로 나중에 등록해, 검사 성공 후 DB 커밋 전 지점에서만 멈춘다. */
    private void pauseAfterRedisCheck(CountDownLatch checked, CountDownLatch resumeCommit) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void beforeCommit(boolean readOnly) {
                assertThat(redisson.getLock("problem:tags:order:lock").isHeldByCurrentThread()).isTrue();
                checked.countDown();
                awaitSignal(resumeCommit);
            }
        });
    }

    /** 작업 스레드의 실패를 호출 스레드에서 검증하며, 성공은 null로 구분한다. */
    private RuntimeException captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (RuntimeException failure) {
            return failure;
        }
    }

    /** Spring·Hibernate 예외의 원인에서 실제 MySQL 오류 번호를 확인한다. */
    private boolean hasMysqlError(Throwable failure, int errorCode) {
        for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
            if (cause instanceof SQLException sql && sql.getErrorCode() == errorCode) {
                return true;
            }
        }
        return false;
    }

    /** 신호 누락을 제한 시간 내 테스트 실패로 전파하고 인터럽트 상태를 보존한다. */
    private void awaitSignal(CountDownLatch signal) {
        try {
            assertThat(signal.await(10, TimeUnit.SECONDS)).as("동시 작업 신호 도착").isTrue();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("동시 작업 신호 대기가 중단됐다", interrupted);
        }
    }

    /** 실제 서비스에서 전파된 충돌이 운영 예외 처리기의 409 계약으로 변환되는지 확인한다. */
    private void assertVersionConflict(RuntimeException failure) {
        assertThat(failure)
            .isInstanceOfAny(
                ProblemException.class,
                PessimisticLockingFailureException.class,
                ObjectOptimisticLockingFailureException.class
            );
        if (failure instanceof ProblemException problem) {
            assertThat(problem.getErrorCode()).isEqualTo(ProblemErrorCode.PROBLEM_VERSION_CONFLICT);
            assertThat(problem.getErrorCode().getStatus()).isEqualTo(HttpStatus.CONFLICT);
        } else {
            var handler = new ProblemExceptionHandler();
            var response
                = failure instanceof PessimisticLockingFailureException locking ? handler.handlePessimisticLock(locking)
                    : handler.handleOptimisticLock((ObjectOptimisticLockingFailureException)failure);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(response.getBody().error().code()).isEqualTo("PROBLEM_VERSION_CONFLICT");
        }
    }

    /** 최종 태그 이름과 순번 전체를 비교해 중복·음수·빈 순번이 남지 않는지 확인한다. */
    private void assertTagSequence(String... names) {
        assertThat(jdbc.queryForList("SELECT name FROM tags ORDER BY display_order", String.class))
            .containsExactly(names);
        assertThat(jdbc.queryForList("SELECT display_order FROM tags ORDER BY display_order", Integer.class))
            .containsExactlyElementsOf(IntStream.range(0, names.length).boxed().toList());
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

    /**
     * 보정 결과가 같아도 원본 요청 순번이 다르면 멱등키 충돌로 응답하는지 검증한다.
     *
     * @throws Exception API 요청 또는 검증 중 오류가 발생한 경우
     */
    @Test
    void idempotencyComparesRequestedOrderBeforeClamping() throws Exception {
        create("original", "기존 태그", Integer.MAX_VALUE);
        mvc
            .perform(
                admin(post(ADMIN_PATH))
                    .header("Idempotency-Key", "original")
                    .content(mapper.writeValueAsString(new TagCreateRequest("기존 태그", Integer.MAX_VALUE - 1)))
            )
            .andExpect(status().is(422))
            .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT request_display_order FROM tag_creation_records", Integer.class))
            .isEqualTo(Integer.MAX_VALUE);
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
    void invalidCreateDoesNotPersistRequestOrTag(String body) throws Exception {
        mvc
            .perform(admin(post(ADMIN_PATH)).header("Idempotency-Key", "invalid").content(body))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_creation_records", Integer.class)).isZero();
    }

    /**
     * 누락·공백·길이 제한을 위반한 멱등키와 이름을 거부하는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    @DisplayName("멱등키 누락·공백·길이 초과와 이름 길이 초과를 입력 오류로 거부한다")
    void missingBlankAndOversizedKeysAndNamesAreRejected() throws Exception {
        String body = "{\"name\":\"태그\",\"displayOrder\":0}";
        mvc.perform(admin(post(ADMIN_PATH)).content(body)).andExpect(status().isBadRequest());
        for (String key : List.of(" ", "k".repeat(101))) {
            String reason = key.isBlank() ? "멱등키를 입력해 주세요." : "멱등키는 100자 이하로 입력해 주세요.";
            mvc
                .perform(admin(post(ADMIN_PATH)).header("Idempotency-Key", key).content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"))
                .andExpect(jsonPath("$.error.details[0].field").value("Idempotency-Key"))
                .andExpect(jsonPath("$.error.details[0].reason").value(reason));
        }
        mvc
            .perform(
                admin(post(ADMIN_PATH))
                    .header("Idempotency-Key", "long-name")
                    .content(mapper.writeValueAsString(new TagCreateRequest("a".repeat(201), 0)))
            )
            .andExpect(status().isBadRequest());
    }

    /**
     * 비 ASCII 문자가 포함된 멱등키를 거부하고 태그와 요청 기록을 남기지 않는지 검증한다.
     *
     * @param key 비 ASCII 문자가 포함된 멱등키
     * @throws Exception 테스트 요청 또는 검증 준비 중 오류가 발생한 경우
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "key-¡",
            "key-é",
            "key-한글",
            "key-😀"
        }
    )
    @DisplayName("비 ASCII 멱등키를 한국어 사유로 거부하고 태그와 요청 기록을 저장하지 않는다")
    void nonAsciiIdempotencyKeysAreRejected(String key) throws Exception {
        mvc
            .perform(
                admin(post(ADMIN_PATH)).header("Idempotency-Key", key).content("{\"name\":\"태그\",\"displayOrder\":0}")
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"))
            .andExpect(jsonPath("$.error.details[0].field").value("Idempotency-Key"))
            .andExpect(jsonPath("$.error.details[0].reason").value("멱등키는 ASCII 문자로 입력해 주세요."));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_creation_records", Integer.class)).isZero();
    }

    /**
     * ASCII 멱등키의 길이 경계와 기호를 허용하고 동일 요청에 최초 결과를 반환하는지 검증한다.
     *
     * @throws Exception 테스트 요청 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void asciiIdempotencyKeysAcceptLengthBoundariesAndSymbols() throws Exception {
        for (String key : List.of("a", "k".repeat(100), "ABC-123_./:~")) {
            String id = create(key, key, 0);
            assertThat(create(key, key, 0)).isEqualTo(id);
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_creation_records", Integer.class)).isEqualTo(3);
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
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_creation_records", Integer.class)).isEqualTo(1);
        String id = create("failed", "스택", 2);
        mvc
            .perform(admin(patch(ADMIN_PATH + "/" + id)).content("{\"name\":\"해시\",\"displayOrder\":9}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("PROBLEM_TAG_DUPLICATED"));
        assertThat(jdbc.queryForObject("SELECT name FROM tags WHERE id = ?", String.class, id)).isEqualTo("스택");
        assertThat(jdbc.queryForObject("SELECT display_order FROM tags WHERE id = ?", Integer.class, id)).isEqualTo(1);
    }

    /**
     * 요청 순번 보정과 태그 이름·순번 변경 및 삭제 이후에도 최초 생성 응답을 반환하는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    @DisplayName("태그 순번이 변경되거나 삭제되어도 최초 보정된 순번을 재응답한다")
    void replayPreservesOriginalResponseAfterTagUpdateAndDelete() throws Exception {
        String original
            = mvc
                .perform(
                    admin(post(ADMIN_PATH))
                        .header("Idempotency-Key", "original")
                        .content("{\"name\":\"해시\",\"displayOrder\":99}")
                )
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.displayOrder").value(0))
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = mapper.readTree(original).path("data").path("tagId").asText();
        create("another", "다른 태그", 0);
        assertThat(displayOrder(id)).isEqualTo(1);
        mvc
            .perform(admin(patch(ADMIN_PATH + "/" + id)).content("{\"name\":\"새 이름\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.displayOrder").value(1));
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT result_display_order FROM tag_creation_records WHERE idempotency_key = ?",
                    Integer.class,
                    "original"
                )
        ).isZero();
        mvc
            .perform(
                admin(post(ADMIN_PATH))
                    .header("Idempotency-Key", "original")
                    .content("{\"displayOrder\":99,\"name\":\"해시\"}")
            )
            .andExpect(status().isCreated())
            .andExpect(content().json(original));
        mvc.perform(admin(delete(ADMIN_PATH + "/" + id))).andExpect(status().isNoContent());
        mvc
            .perform(
                admin(post(ADMIN_PATH))
                    .header("Idempotency-Key", "original")
                    .content("{\"name\":\"해시\",\"displayOrder\":99}")
            )
            .andExpect(status().isCreated())
            .andExpect(content().json(original));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags WHERE id = ?", Integer.class, id)).isZero();
    }

    /**
     * 원본 요청 이름과 순번을 보관하고 이름의 공백·대소문자 차이를 구분하는지 검증한다.
     *
     * @param name 최초 요청에 사용할 원본 태그 이름
     * @throws Exception 테스트 요청 또는 검증 준비 중 오류가 발생한 경우
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            " 그리디 ",
            "Hash"
        }
    )
    void idempotencyPreservesOriginalNameAndComparesItExactly(String name) throws Exception {
        String id = create("original-values", name, 37);
        assertThat(jdbc.queryForObject("SELECT request_name FROM tag_creation_records", String.class)).isEqualTo(name);
        assertThat(jdbc.queryForObject("SELECT request_display_order FROM tag_creation_records", Integer.class))
            .isEqualTo(37);
        assertThat(jdbc.queryForObject("SELECT name FROM tags WHERE id = ?", String.class, id)).isEqualTo(name.strip());
        assertThat(create("original-values", name, 37)).isEqualTo(id);

        String changedName = name.startsWith(" ") ? name.strip() : "hash";
        mvc
            .perform(
                admin(post(ADMIN_PATH))
                    .header("Idempotency-Key", "original-values")
                    .content(mapper.writeValueAsString(new TagCreateRequest(changedName, 37)))
            )
            .andExpect(status().is(422))
            .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags", Integer.class)).isEqualTo(1);
        assertThat(displayOrder(id)).isZero();
    }

    /**
     * 최대 길이의 한글 이름과 정수 최대 순번도 원본 요청으로 보관하고 재사용하는지 검증한다.
     *
     * @throws Exception 테스트 요청 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void idempotencyStoresMaximumLengthNameAndOrder() throws Exception {
        String name = "가".repeat(200);
        String id = create("maximum-values", name, Integer.MAX_VALUE);
        assertThat(create("maximum-values", name, Integer.MAX_VALUE)).isEqualTo(id);
        assertThat(jdbc.queryForObject("SELECT request_name FROM tag_creation_records", String.class)).isEqualTo(name);
        assertThat(jdbc.queryForObject("SELECT request_display_order FROM tag_creation_records", Integer.class))
            .isEqualTo(Integer.MAX_VALUE);
    }

    /**
     * 멱등키의 본문 충돌과 관리자별 범위 및 대소문자 구분을 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void sameKeyWithDifferentBodyConflictsAndKeysAreScopedByAdminAndCase() throws Exception {
        create("key", "해시", 0);
        mvc
            .perform(
                admin(post(ADMIN_PATH)).header("Idempotency-Key", "key").content("{\"name\":\"해시\",\"displayOrder\":1}")
            )
            .andExpect(status().is(422))
            .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_KEY_CONFLICT"));
        mvc
            .perform(
                post(ADMIN_PATH)
                    .header("X-User-Id", "other-admin")
                    .header("X-User-Role", "ADMIN")
                    .header("Idempotency-Key", "key")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"name\":\"스택\",\"displayOrder\":0}")
            )
            .andExpect(status().isCreated());
        create("KEY", "큐", 0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags", Integer.class)).isEqualTo(3);
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

    /**
     * 동일 키의 동시 요청이 태그 하나를 생성하고 같은 ID를 반환하는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void concurrentSameKeyCreatesOneTagAndReturnsSameId() throws Exception {
        List<String> ids
            = race(
                () -> tagService.create("admin", "same", new TagCreateRequest("해시", 0)).tagId(),
                () -> tagService.create("admin", "same", new TagCreateRequest("해시", 0)).tagId()
            );
        assertThat(ids.get(0)).isEqualTo(ids.get(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_creation_records", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("같은 이름의 동시 등록은 하나만 성공하고 나머지는 중복 오류를 반환한다")
    void concurrentDuplicateNamesCreateOneTagAndRejectTheOtherRequest() throws Exception {
        List<String> outcomes = race(() -> createOutcome("first"), () -> createOutcome("second"));
        assertThat(outcomes).containsExactlyInAnyOrder("CREATED", "PROBLEM_TAG_DUPLICATED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_creation_records", Integer.class)).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(
        value = Isolation.class,
        names = {
            "REPEATABLE_READ",
            "READ_COMMITTED"
        }
    )
    @Timeout(30)
    @DisplayName("같은 관리자·키의 다른 본문 동시 요청은 하나만 생성하고 나머지는 422로 거부한다")
    void concurrentDifferentBodiesKeepOnlyWinningRecord(Isolation isolation) throws Exception {
        // given
        Callable<String> first = () -> createWithConflictingBody(isolation, "첫 요청");
        Callable<String> other = () -> createWithConflictingBody(isolation, "다른 요청");

        // when
        List<String> outcomes = race(first, other);

        // then
        assertThat(outcomes).containsExactlyInAnyOrder("CREATED", "IDEMPOTENCY_KEY_CONFLICT");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags", Integer.class)).isEqualTo(1);
        var tag = jdbc.queryForMap("SELECT id, name, display_order FROM tags");
        var record = jdbc.queryForMap("SELECT * FROM tag_creation_records");
        assertThat(record.get("request_name")).isIn("첫 요청", "다른 요청");
        assertThat(record.get("request_name")).isEqualTo(tag.get("name"));
        assertThat(record.get("result_name")).isEqualTo(tag.get("name"));
        assertThat(record.get("result_tag_id")).isEqualTo(tag.get("id"));
        assertThat(record.get("result_display_order")).isEqualTo(0);
        assertTagSequence((String)tag.get("name"));
    }

    @ParameterizedTest
    @EnumSource(
        value = Isolation.class,
        names = {
            "REPEATABLE_READ",
            "READ_COMMITTED"
        }
    )
    @Timeout(30)
    @DisplayName("기록 잠금 대기 중 만료되면 잠금 획득 후 시각으로 새 요청을 처리한다")
    void expiryIsRecheckedAfterWaitingForRecordLock(Isolation isolation) throws Exception {
        // given
        String originalId = create("expiry-wait", "기존 태그", 0);
        Instant beforeExpiry = Instant.parse("2026-10-13T23:59:59Z");
        Instant afterExpiry = Instant.parse("2026-10-14T00:00:01Z");
        var currentTime = new AtomicReference<>(beforeExpiry);
        when(clock.instant()).thenAnswer(invocation -> currentTime.get());
        var connectionId = new AtomicLong();
        var started = new CountDownLatch(1);
        var waitingTransaction = new TransactionTemplate(transactionManager);
        waitingTransaction.setIsolationLevel(isolation.value());

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // when
            var pending = new TransactionTemplate(transactionManager).execute(status -> {
                jdbc.queryForObject("""
                    SELECT id FROM tag_creation_records
                    WHERE admin_id = 'admin' AND idempotency_key = 'expiry-wait' FOR UPDATE
                    """, Long.class);
                var future = executor.submit(() -> waitingTransaction.execute(waiting -> {
                    assertThat(jdbc.queryForObject("SELECT @@session.transaction_isolation", String.class))
                        .isEqualTo(isolation.name().replace('_', '-'));
                    connectionId.set(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
                    started.countDown();
                    return tagService.create("admin", "expiry-wait", new TagCreateRequest("새 태그", 1));
                }));
                awaitSignal(started);
                // upsert가 실제 DB 잠금을 기다리는 동안 만료 경계를 넘긴다.
                awaitDatabaseLockWait(connectionId.get());
                currentTime.set(afterExpiry);
                return future;
            });
            var response = pending.get(10, TimeUnit.SECONDS);

            // then
            assertThat(response.tagId()).isNotEqualTo(originalId);
            var record = jdbc.queryForMap("SELECT * FROM tag_creation_records");
            assertThat(record.get("request_name")).isEqualTo("새 태그");
            assertThat(record.get("result_tag_id").toString()).isEqualTo(response.tagId());
            assertThat(jdbc.queryForObject("SELECT expires_at FROM tag_creation_records", LocalDateTime.class))
                .isEqualTo(LocalDateTime.ofInstant(afterExpiry, clock.getZone()).plusHours(24));
            assertTagSequence("기존 태그", "새 태그");
        }
    }

    @ParameterizedTest
    @ValueSource(
        booleans = {
            false,
            true
        }
    )
    @Timeout(30)
    @DisplayName("응답 기록까지 flush한 뒤 Redis 검사가 실패하면 태그·순번·멱등 기록을 모두 복원한다")
    void lostRedisOwnershipRollsBackCompletedRecord(boolean renewExpiredRecord) throws Exception {
        // given
        create("existing", "기존 태그", 0);
        create("last", "끝 태그", 1);
        String key = renewExpiredRecord ? "existing" : "new-record";
        if (renewExpiredRecord) {
            when(clock.instant()).thenReturn(Instant.parse("2026-10-14T00:00:00Z"));
        }
        var originalTags = jdbc.queryForList("SELECT * FROM tags ORDER BY id");
        var originalRecords = jdbc.queryForList("SELECT * FROM tag_creation_records ORDER BY id");

        // when & then
        RuntimeException failure
            = captureFailure(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                var response = tagService.create("admin", key, new TagCreateRequest("새 태그", 0));
                tags.flush();
                var record = jdbc.queryForMap("SELECT * FROM tag_creation_records WHERE idempotency_key = ?", key);
                assertThat(record.get("result_tag_id").toString()).isEqualTo(response.tagId());
                assertThat(record.get("result_name")).isEqualTo(response.name());
                assertThat(record.get("result_display_order")).isEqualTo(response.displayOrder());
                assertTagSequence("새 태그", "기존 태그", "끝 태그");
                assertThat(redisson.getLock("problem:tags:order:lock").forceUnlock()).isTrue();
            }));

        // then
        assertThat(failure).isInstanceOf(ProblemException.class);
        assertVersionConflict(failure);
        assertThat(jdbc.queryForList("SELECT * FROM tags ORDER BY id")).isEqualTo(originalTags);
        assertThat(jdbc.queryForList("SELECT * FROM tag_creation_records ORDER BY id")).isEqualTo(originalRecords);
    }

    @ParameterizedTest
    @EnumSource(
        value = Isolation.class,
        names = {
            "REPEATABLE_READ",
            "READ_COMMITTED"
        }
    )
    @Timeout(30)
    @DisplayName("Redis 검사 후 소유권을 잃어도 같은 멱등키 요청은 DB 기록 잠금으로 보호한다")
    void recordLockProtectsReplayAfterSuccessfulRedisCheck(Isolation isolation) throws Exception {
        // given
        create("seed", "기존 태그", 0);
        var request = new TagCreateRequest("새 태그", 0);
        var checked = new CountDownLatch(1);
        var resumeCommit = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            // when & then
            var first = executor.submit(() -> inTransaction(isolation, () -> {
                var response = tagService.create("admin", "shared-record", request);
                tags.flush();
                assertThat(jdbc.queryForObject("""
                    SELECT result_tag_id FROM tag_creation_records WHERE idempotency_key = 'shared-record'
                    """, String.class)).isEqualTo(response.tagId());
                pauseAfterRedisCheck(checked, resumeCommit);
                return response;
            }));
            try {
                awaitSignal(checked);
                assertThat(redisson.getLock("problem:tags:order:lock").forceUnlock()).isTrue();
                var competing = executor.submit(() -> captureFailure(() -> inTransaction(isolation, () -> {
                    jdbc.update("INSERT INTO lock_transaction_probe (id, payload) VALUES (1, 'same-key')");
                    return tagService.create("admin", "shared-record", request);
                })));

                // then
                RuntimeException failure = competing.get(10, TimeUnit.SECONDS);
                assertThat(failure).isInstanceOf(PessimisticLockingFailureException.class);
                assertThat(hasMysqlError(failure, 1205)).isTrue();
                assertVersionConflict(failure);
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM lock_transaction_probe", Integer.class)).isZero();
            } finally {
                resumeCommit.countDown();
            }
            var committed = first.get(10, TimeUnit.SECONDS);

            // when & then
            assertThat(tagService.create("admin", "shared-record", request)).isEqualTo(committed);
            var record = jdbc.queryForMap("""
                SELECT * FROM tag_creation_records WHERE idempotency_key = 'shared-record'
                """);
            assertThat(record.get("result_tag_id").toString()).isEqualTo(committed.tagId());
            assertThat(record.get("result_name")).isEqualTo(committed.name());
            assertThat(record.get("result_display_order")).isEqualTo(committed.displayOrder());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_creation_records", Integer.class)).isEqualTo(2);
            assertTagSequence("새 태그", "기존 태그");
        }
    }

    /** 같은 키의 다른 본문을 실제 서비스에 전달하고 충돌의 오류 코드·HTTP 상태 정의를 확인한다. */
    private String createWithConflictingBody(Isolation isolation, String name) {
        try {
            inTransaction(
                isolation,
                () -> tagService.create("admin", "different-bodies", new TagCreateRequest(name, 0))
            );
            return "CREATED";
        } catch (ProblemException conflict) {
            assertThat(conflict.getErrorCode()).isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT);
            assertThat(conflict.getErrorCode().getStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_CONTENT);
            return conflict.getErrorCode().getCode();
        }
    }

    /** 서비스 SQL의 실제 DB 잠금 대기를 테스트 전용 관리 연결에서 관측해 순서를 고정한다. */
    private void awaitDatabaseLockWait(long connectionId) {
        try (
            var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
            var statement = connection.prepareStatement("""
                SELECT COUNT(*) FROM performance_schema.data_lock_waits w
                JOIN performance_schema.threads t ON t.THREAD_ID = w.REQUESTING_THREAD_ID
                WHERE t.PROCESSLIST_ID = ?
                """)
        ) {
            statement.setLong(1, connectionId);
            statement.setQueryTimeout(2);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            do {
                try (var result = statement.executeQuery()) {
                    if (result.next() && result.getInt(1) > 0) {
                        return;
                    }
                }
                Thread.yield();
            } while (System.nanoTime() < deadline);
            throw new AssertionError("멱등 기록의 DB 잠금 대기를 확인하지 못했다");
        } catch (SQLException exception) {
            throw new IllegalStateException("테스트 DB 잠금 대기를 조회하지 못했다", exception);
        }
    }

    /**
     * 만료 전 재요청이 최초 24시간의 유효 기간을 연장하지 않는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void replayBeforeExpiryDoesNotExtendTheOriginal24Hours() throws Exception {
        String originalId = create("ttl", "해시", 0);
        LocalDateTime expiresAt
            = jdbc.queryForObject("SELECT expires_at FROM tag_creation_records", LocalDateTime.class);
        assertThat(expiresAt).isEqualTo(LocalDateTime.of(2026, 10, 14, 9, 0));
        when(clock.instant()).thenReturn(Instant.parse("2026-10-13T23:59:59.999999Z"));
        assertThat(create("ttl", "해시", 0)).isEqualTo(originalId);
        mvc
            .perform(
                admin(post(ADMIN_PATH))
                    .header("Idempotency-Key", "ttl")
                    .content("{\"name\":\"다른 태그\",\"displayOrder\":0}")
            )
            .andExpect(status().is(422));
        assertThat(jdbc.queryForObject("SELECT expires_at FROM tag_creation_records", LocalDateTime.class))
            .isEqualTo(expiresAt);
    }

    /**
     * 삭제 스케줄러 실행 전에도 만료된 키를 새 요청으로 검증하는지 확인한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void expiredRequestIsRevalidatedWithoutWaitingForCleanup() throws Exception {
        String originalId = create("ttl", "해시", 0);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-14T00:00:00Z"));
        // 같은 태그가 남아 있으므로 최초 응답 재사용이 아니라 이름 중복 검증을 거친다.
        mvc
            .perform(
                admin(post(ADMIN_PATH)).header("Idempotency-Key", "ttl").content("{\"name\":\"해시\",\"displayOrder\":0}")
            )
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error.code").value("PROBLEM_TAG_DUPLICATED"));
        String newId = create("ttl", "스택", 1);
        assertThat(newId).isNotEqualTo(originalId);
        assertThat(jdbc.queryForObject("SELECT request_name FROM tag_creation_records", String.class)).isEqualTo("스택");
        assertThat(jdbc.queryForObject("SELECT request_display_order FROM tag_creation_records", Integer.class))
            .isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_creation_records", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT expires_at FROM tag_creation_records", LocalDateTime.class))
            .isEqualTo(LocalDateTime.of(2026, 10, 15, 9, 0));
        assertThat(jdbc.queryForObject("SELECT result_display_order FROM tag_creation_records", Integer.class))
            .isEqualTo(1);
        mvc
            .perform(
                admin(post(ADMIN_PATH)).header("Idempotency-Key", "ttl").content("{\"name\":\"스택\",\"displayOrder\":1}")
            )
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.data.tagId").value(newId))
            .andExpect(jsonPath("$.data.displayOrder").value(1));
    }

    /**
     * 원래 태그가 삭제된 후 만료된 키로 새 태그를 생성할 수 있는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void expiredKeyWithSameBodyCreatesNewTagAfterOriginalTagWasDeleted() throws Exception {
        String originalId = create("ttl", "해시", 0);
        mvc.perform(admin(delete(ADMIN_PATH + "/" + originalId))).andExpect(status().isNoContent());
        when(clock.instant()).thenReturn(Instant.parse("2026-10-14T00:00:00Z"));
        assertThat(create("ttl", "해시", 0)).isNotEqualTo(originalId);
    }

    /**
     * 스케줄러가 만료 요청만 삭제하고 태그와 유효한 요청은 유지하는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void schedulerDeletesOnlyExpiredRequestsAndKeepsTags() throws Exception {
        // given
        create("old", "해시", 0);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-13T01:00:00Z"));
        create("fresh", "스택", 1);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-14T00:00:00Z"));
        // when
        cleanupScheduler.deleteExpiredRecords();
        // then
        assertThat(jdbc.queryForList("SELECT idempotency_key FROM tag_creation_records", String.class))
            .containsExactly("fresh");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags", Integer.class)).isEqualTo(2);
        cleanupScheduler.deleteExpiredRecords();
        assertThat(jdbc.queryForList("SELECT idempotency_key FROM tag_creation_records", String.class))
            .containsExactly("fresh");
    }

    /**
     * 후보 조회 뒤 유효 기간이 연장된 기록을 삭제하지 않는지 검증한다.
     */
    @Test
    @DisplayName("후보 조회 후 만료 시각이 연장된 기록은 삭제하지 않는다")
    void cleanupRechecksExpiryAfterCandidateSelection() throws Exception {
        // given
        create("extended", "해시", 0);
        Instant cleanupTime = Instant.parse("2026-10-14T00:00:00Z");
        AtomicBoolean candidateTransactionObserved = new AtomicBoolean();
        AtomicBoolean renewed = new AtomicBoolean();
        when(clock.instant()).thenAnswer(invocation -> {
            if (TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                candidateTransactionObserved.set(true);
            } else if (candidateTransactionObserved.get() && renewed.compareAndSet(false, true)) {
                // 실제 후보 조회가 끝난 뒤, 첫 건별 삭제를 시작하기 전 다른 쓰기 트랜잭션으로 연장한다.
                assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
                var renewalTransaction = new TransactionTemplate(transactionManager);
                renewalTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
                renewalTransaction
                    .executeWithoutResult(
                        status -> jdbc
                            .update(
                                "UPDATE tag_creation_records SET expires_at = ? WHERE idempotency_key = 'extended'",
                                LocalDateTime.ofInstant(cleanupTime, ZoneId.of("Asia/Seoul")).plusHours(1)
                            )
                    );
            }
            return cleanupTime;
        });

        // when
        cleanupScheduler.deleteExpiredRecords();

        // then
        assertThat(candidateTransactionObserved).isTrue();
        assertThat(renewed).isTrue();
        assertThat(jdbc.queryForList("SELECT idempotency_key FROM tag_creation_records", String.class))
            .containsExactly("extended");
    }

    /**
     * 만료 정리가 실행마다 최대 500개만 삭제하는지 검증한다.
     */
    @Test
    void cleanupDeletesAtMost500RequestsPerRun() {
        // given
        LocalDateTime expired = LocalDateTime.now(clock).minusHours(1);
        List<Object[]> rows
            = IntStream
                .range(0, 501)
                .mapToObj(
                    index -> new Object[] {
                        "batch-" + index,
                        expired,
                        expired.minusHours(24),
                        expired.minusHours(24)
                    }
                )
                .toList();
        jdbc.batchUpdate("""
            INSERT INTO tag_creation_records
                (admin_id, idempotency_key, request_name, request_display_order, expires_at,
                    created_at, updated_at, version, result_tag_id, result_name, result_display_order)
            VALUES ('admin', ?, '태그', 1, ?, ?, ?, 1, 1, '태그', 1)
            """, rows);
        // when
        cleanupScheduler.deleteExpiredRecords();
        // then
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_creation_records", Integer.class)).isEqualTo(1);
        cleanupScheduler.deleteExpiredRecords();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_creation_records", Integer.class)).isZero();
    }

    /**
     * 한 건의 실제 DB 삭제 실패가 앞선 삭제를 되돌리지 않고 다음 후보 처리를 허용하는지 검증한다.
     *
     * @throws SQLException 테스트 전용 트리거 생성·정리에 실패한 경우
     */
    @Test
    @DisplayName("한 기록의 삭제 실패가 앞선 삭제를 되돌리지 않고 다음 기록 처리를 허용한다")
    void cleanupFailureRollsBackOnlyItsOwnTransaction() throws SQLException {
        // given
        LocalDateTime expired = LocalDateTime.now(clock).minusHours(1);
        List<Object[]> rows
            = Stream
                .of("delete-before", "fail-delete", "delete-after")
                .map(
                    key -> new Object[] {
                        key,
                        expired,
                        expired.minusHours(24),
                        expired.minusHours(24)
                    }
                )
                .toList();
        jdbc.batchUpdate("""
            INSERT INTO tag_creation_records
                (admin_id, idempotency_key, request_name, request_display_order, expires_at,
                    created_at, updated_at, version, result_tag_id, result_name, result_display_order)
            VALUES ('admin', ?, '태그', 1, ?, ?, ?, 1, 1, '태그', 1)
            """, rows);
        String trigger = "tr_test_fail_tag_creation_record_delete";
        // DDL만 컨테이너 관리자 연결로 실행하고, 실제 삭제는 서비스 계정과 트랜잭션을 그대로 사용한다.
        try (
            var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), "root", MYSQL.getPassword());
            var statement = connection.createStatement()
        ) {
            Throwable testFailure = null;
            try {
                statement.execute("DROP TRIGGER IF EXISTS " + trigger);
                statement.execute("""
                    CREATE TRIGGER tr_test_fail_tag_creation_record_delete
                    BEFORE DELETE ON tag_creation_records
                    FOR EACH ROW
                    BEGIN
                        IF OLD.idempotency_key = 'fail-delete' THEN
                            SIGNAL SQLSTATE '23000' SET MESSAGE_TEXT = 'intentional cleanup test failure';
                        END IF;
                    END
                    """);

                // when
                cleanupScheduler.deleteExpiredRecords();

                // then
                assertThat(jdbc.queryForList("SELECT idempotency_key FROM tag_creation_records", String.class))
                    .containsExactly("fail-delete");
            } catch (SQLException | RuntimeException | Error failure) {
                testFailure = failure;
                throw failure;
            } finally {
                try {
                    statement.execute("DROP TRIGGER IF EXISTS " + trigger);
                } catch (SQLException cleanupFailure) {
                    if (testFailure == null) {
                        throw cleanupFailure;
                    }
                    testFailure.addSuppressed(cleanupFailure);
                }
            }
        }
    }

    /**
     * 정리가 처리 중인 요청을 건너뛰고 갱신된 기록을 보존하는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void cleanupSkipsLockedRequestAndDoesNotDeleteItsRenewedRecord() throws Exception {
        // given
        create("locked", "해시", 0);
        create("unlocked", "스택", 1);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-14T00:00:00Z"));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch renew = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var pending = executor.submit(() -> new TransactionTemplate(transactionManager).execute(transaction -> {
                jdbc.queryForObject("""
                    SELECT id FROM tag_creation_records
                    WHERE admin_id = 'admin' AND idempotency_key = 'locked' FOR UPDATE
                    """, Long.class);
                locked.countDown();
                try {
                    if (!renew.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Cleanup did not finish");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return tagService.create("admin", "locked", new TagCreateRequest("갱신된 태그", 2)).tagId();
            }));
            try {
                assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
                // when
                cleanupScheduler.deleteExpiredRecords();
            } finally {
                renew.countDown();
            }
            String renewedId = pending.get(10, TimeUnit.SECONDS);
            // then
            cleanupScheduler.deleteExpiredRecords();
            assertThat(jdbc.queryForObject("SELECT result_tag_id FROM tag_creation_records", String.class))
                .isEqualTo(renewedId);
        }
    }

    /**
     * 만료된 동일 키의 동시 요청도 새 태그 하나만 생성하는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void concurrentExpiredKeyRequestsStillCreateOnlyOneNewTag() throws Exception {
        create("same", "해시", 0);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-14T00:00:00Z"));
        List<String> ids
            = race(
                () -> tagService.create("admin", "same", new TagCreateRequest("스택", 1)).tagId(),
                () -> tagService.create("admin", "same", new TagCreateRequest("스택", 1)).tagId()
            );
        assertThat(ids.get(0)).isEqualTo(ids.get(1));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_creation_records", Integer.class)).isEqualTo(1);
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
