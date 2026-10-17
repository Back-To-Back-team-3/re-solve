package com.backtoback.problem.tag;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.problem.global.lock.RedisTransactionLock;
import com.backtoback.problem.tag.dto.request.TagCreateRequest;
import com.backtoback.problem.tag.dto.request.TagUpdateRequest;
import com.backtoback.problem.tag.repository.TagRepository;
import com.backtoback.problem.tag.scheduler.TagCreationRecordCleanupScheduler;
import com.backtoback.problem.tag.service.TagCreationRecordService;
import com.backtoback.problem.tag.service.TagOrderService;
import com.backtoback.problem.tag.service.TagService;

import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
        "spring.cloud.aws.region.static=ap-northeast-2",
        "spring.cloud.aws.credentials.access-key=test",
        "spring.cloud.aws.credentials.secret-key=test"
    }
)
@AutoConfigureMockMvc
@Testcontainers
class TagApiTests {
    private static final String ADMIN_PATH = "/api/v1/admin/problems/tags";
    private static final String LIST_PATH = "/api/v1/problems/tags";

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("problem_db");

    @Container
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
        // 문제 회차 구현 전이므로 테스트 전용 참조 테이블로 FK 삭제 제약을 검증한다.
        jdbc.execute("""
            CREATE TABLE IF NOT EXISTS tag_usage_probe (
                id BIGINT PRIMARY KEY,
                tag_id BIGINT NOT NULL,
                CONSTRAINT fk_problem_revision_tags_tag FOREIGN KEY (tag_id) REFERENCES tags(id) ON DELETE RESTRICT
            )
            """);
        jdbc.update("DELETE FROM tag_usage_probe");
        jdbc.update("DELETE FROM tags");
        jdbc.update("DELETE FROM tag_creation_records");
    }

    /**
     * 태그 생성·부분 수정·삭제 응답과 공통 엔티티 필드 갱신을 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void createPatchAndDeleteFollowApiContractAndAuditFields() throws Exception {
        String id = create("hash", "해시", 5);
        mvc
            .perform(admin(patch(ADMIN_PATH + "/" + id)).content("{\"name\":\"그리디\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.message").value("태그가 수정되었습니다."))
            .andExpect(jsonPath("$.data.tagId").value(id))
            .andExpect(jsonPath("$.data.name").value("그리디"));
        assertThat(jdbc.queryForObject("SELECT display_order FROM tags WHERE id = ?", Integer.class, id)).isZero();
        mvc
            .perform(admin(patch(ADMIN_PATH + "/" + id)).content("{\"displayOrder\":2}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.name").value("그리디"));
        assertThat(jdbc.queryForObject("SELECT display_order FROM tags WHERE id = ?", Integer.class, id)).isZero();
        assertThat(jdbc.queryForObject("SELECT version FROM tags WHERE id = ?", Long.class, id)).isEqualTo(1L);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM tags WHERE created_at IS NOT NULL AND updated_at >= created_at",
                    Integer.class
                )
        ).isEqualTo(1);
        mvc
            .perform(admin(delete(ADMIN_PATH + "/" + id)))
            .andExpect(status().isNoContent())
            .andExpect(content().string(""));
    }

    /**
     * 태그 목록의 기본 정렬·검색·페이지 처리·허용 정렬을 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void listSupportsDefaultOrderKeywordPagingAndAllowedSort() throws Exception {
        String second = create("stack", "스택/큐", 2);
        String first = create("hash", "해시", 0);
        mvc
            .perform(user(get(LIST_PATH)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].tagId").value(first))
            .andExpect(jsonPath("$.data.size").value(100))
            .andExpect(jsonPath("$.data.totalElements").value(2));
        mvc
            .perform(user(get(LIST_PATH).param("size", "1").param("sort", "displayOrder,desc")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content[0].tagId").value(second))
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

    /**
     * 중복 순번 등록 시 기존 태그의 순번과 수정 정보가 갱신되는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
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

    /**
     * 범위를 넘는 순번으로 등록하면 마지막에 추가하고 기존 태그의 순번을 유지하는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
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

    /**
     * 이름 중복으로 등록에 실패하면 순번 이동과 요청 기록도 롤백되는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
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

    /**
     * 같은 순번으로 동시에 등록해도 서로 다른 위치를 차지하는지 검증한다.
     *
     * @param hasExistingTag 기존 태그가 있는 상태로 검증할지 여부
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @ParameterizedTest
    @ValueSource(
        booleans = {
            false,
            true
        }
    )
    void concurrentCreatesAtSameOrderKeepDistinctPositions(boolean hasExistingTag) throws Exception {
        if (hasExistingTag) {
            create("existing", "기존 태그", 2);
        }
        List<String> ids
            = race(
                () -> tagService.create("admin", "first", new TagCreateRequest("새 태그 A", 0)).tagId(),
                () -> tagService.create("admin", "second", new TagCreateRequest("새 태그 B", 0)).tagId()
            );
        assertThat(ids.get(0)).isNotEqualTo(ids.get(1));
        List<Integer> orders
            = jdbc.queryForList("SELECT display_order FROM tags ORDER BY display_order", Integer.class);
        assertThat(orders).containsExactlyElementsOf(hasExistingTag ? List.of(0, 1, 2) : List.of(0, 1));
    }

    /**
     * 서로 다른 Redis 클라이언트를 사용하는 태그 등록도 직렬화되는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void separateRedisClientsSerializeConcurrentTagCreation() throws Exception {
        RedissonClient otherClient = Redisson.create(new Config(redisson.getConfig()));
        try {
            TagService otherService
                = new TagService(
                    tags,
                    creationRecords,
                    new TagOrderService(tags, new RedisTransactionLock(otherClient))
                );
            var transaction = new TransactionTemplate(transactionManager);
            race(
                () -> tagService.create("admin", "first", new TagCreateRequest("첫 태그", 2)).tagId(),
                () -> transaction
                    .execute(
                        status -> otherService.create("admin", "second", new TagCreateRequest("두 번째 태그", 2)).tagId()
                    )
            );
            assertThat(jdbc.queryForList("SELECT display_order FROM tags ORDER BY display_order", Integer.class))
                .containsExactly(0, 1);
        } finally {
            otherClient.shutdown();
        }
    }

    /**
     * Redis 잠금이 DB 트랜잭션 종료까지 유지되고 이후 해제되는지 검증한다.
     *
     * @param rollback 트랜잭션을 커밋하지 않고 롤백할지 여부
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @ParameterizedTest
    @ValueSource(
        booleans = {
            false,
            true
        }
    )
    void redisLockIsHeldUntilTransactionCompletesAndReleasedAfterwards(boolean rollback) throws Exception {
        String first = create("first", "첫 태그", 0);
        String following = create("following", "뒤 태그", 1);
        RedissonClient otherClient = Redisson.create(new Config(redisson.getConfig()));
        var otherLock = otherClient.getLock("problem:tags:order:lock");
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(transaction -> {
                tagService.create("admin", "transaction", new TagCreateRequest("태그", 1));
                assertThat(displayOrder(first)).isZero();
                assertThat(displayOrder(following)).isEqualTo(2);
                assertThat(otherLock.tryLock()).isFalse();
                if (rollback) {
                    transaction.setRollbackOnly();
                }
            });
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

    /**
     * 잠금 대기 시간이 초과되면 태그와 요청 기록이 저장되지 않는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
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

    /**
     * 정수 최대 순번을 요청해도 등록 위치를 마지막으로 보정하는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
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

    /**
     * 앞뒤로 이동할 때 이동 구간의 순번만 조정하고 전체 순번을 연속되게 유지하는지 검증한다.
     *
     * @param moveForward 더 큰 순번으로 이동할지 여부
     * @throws Exception 테스트 요청 또는 검증 준비 중 오류가 발생한 경우
     */
    @ParameterizedTest
    @ValueSource(
        booleans = {
            false,
            true
        }
    )
    void updatingToOccupiedOrderShiftsOtherTags(boolean moveForward) throws Exception {
        String first = create("first", "첫 태그", 0);
        String second = create("second", "두 번째 태그", 1);
        String third = create("third", "세 번째 태그", 2);
        String moving = moveForward ? first : third;
        mvc
            .perform(
                admin(patch(ADMIN_PATH + "/" + moving))
                    .content(mapper.writeValueAsString(new TagUpdateRequest(null, moveForward ? 2 : 0)))
            )
            .andExpect(status().isOk());
        assertThat(displayOrder(moving)).isEqualTo(moveForward ? 2 : 0);
        assertThat(displayOrder(second)).isEqualTo(moveForward ? 0 : 2);
        assertThat(displayOrder(moveForward ? third : first)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM tags WHERE id = ?", Long.class, moving)).isEqualTo(2L);
        assertThat(jdbc.queryForObject("SELECT version FROM tags WHERE id = ?", Long.class, second)).isEqualTo(2L);
    }

    /**
     * 이름만 수정하거나 현재 순번을 다시 전달하면 다른 태그를 이동하지 않는지 검증한다.
     *
     * @param body 순번 유지 여부를 확인할 수정 요청 본문
     * @throws Exception 테스트 요청 또는 검증 준비 중 오류가 발생한 경우
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "{\"displayOrder\":0}",
            "{\"name\":\"새 이름\"}"
        }
    )
    void unchangedOrderDoesNotShiftOtherTags(String body) throws Exception {
        String first = create("first", "첫 태그", 0);
        String second = create("second", "두 번째 태그", 1);
        mvc.perform(admin(patch(ADMIN_PATH + "/" + first)).content(body)).andExpect(status().isOk());
        assertThat(displayOrder(first)).isZero();
        assertThat(displayOrder(second)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT version FROM tags WHERE id = ?", Long.class, second)).isZero();
    }

    /**
     * 마지막을 넘는 순번으로 수정하면 끝으로 이동하고 이전 위치의 빈 순번을 메우는지 검증한다.
     *
     * @throws Exception 테스트 요청 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void updatingBeyondLastOrderMovesToEndAndClosesOldGap() throws Exception {
        String moving = create("moving", "이동할 태그", 1);
        String following = create("following", "뒤 태그", 5);
        mvc.perform(admin(patch(ADMIN_PATH + "/" + moving)).content("{\"displayOrder\":3}")).andExpect(status().isOk());
        assertThat(displayOrder(moving)).isEqualTo(1);
        assertThat(displayOrder(following)).isZero();
    }

    /**
     * 정수 최소 순번으로 수정하면 첫 위치로 보정하고 기존 태그를 뒤로 이동하는지 검증한다.
     *
     * @throws Exception 테스트 요청 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void minimumRequestedOrderMovesTagToFront() throws Exception {
        String existing = create("existing", "기존 태그", 2);
        String moving = create("moving", "이동할 태그", Integer.MAX_VALUE);
        mvc
            .perform(admin(patch(ADMIN_PATH + "/" + moving)).content("{\"displayOrder\":-2147483648}"))
            .andExpect(status().isOk());
        assertThat(displayOrder(moving)).isZero();
        assertThat(displayOrder(existing)).isEqualTo(1);
    }

    /**
     * 이름 중복으로 수정이 실패하면 다른 태그의 순번 이동도 롤백되는지 검증한다.
     *
     * @throws Exception 테스트 요청 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
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

    /**
     * 정수 최대 순번으로 수정하면 실패 대신 마지막 위치로 이동하는지 검증한다.
     *
     * @throws Exception 테스트 요청 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
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

    /**
     * 서로 다른 태그를 같은 순번으로 동시에 수정해도 순번 이동이 직렬화되는지 검증한다.
     *
     * @throws Exception 동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void concurrentUpdatesToSameOrderKeepDistinctPositions() throws Exception {
        String first = create("first", "첫 태그", 1);
        String second = create("second", "두 번째 태그", 2);
        create("existing", "기존 태그", 3);
        race(
            () -> tagService.update(Long.parseLong(first), new TagUpdateRequest(null, 3)).tagId(),
            () -> tagService.update(Long.parseLong(second), new TagUpdateRequest(null, 3)).tagId()
        );
        assertThat(jdbc.queryForList("SELECT display_order FROM tags ORDER BY display_order", Integer.class))
            .containsExactly(0, 1, 2);
    }

    /**
     * 중간 태그를 삭제하면 이후 순번만 한 칸씩 당기고 수정 정보를 갱신하는지 검증한다.
     *
     * @throws Exception 테스트 요청 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
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

    /**
     * 첫 태그를 삭제하면 이후 순번을 당기고 마지막 태그를 삭제하면 앞 순번을 유지하는지 검증한다.
     *
     * @param deleteFirst 첫 태그를 삭제할지 여부
     * @throws Exception 테스트 요청 또는 검증 준비 중 오류가 발생한 경우
     */
    @ParameterizedTest
    @ValueSource(
        booleans = {
            false,
            true
        }
    )
    void deletingFirstOrLastTagHandlesOrderBoundaries(boolean deleteFirst) throws Exception {
        String first = create("first", "첫 태그", 1);
        String last = create("last", "마지막 태그", Integer.MAX_VALUE);
        mvc.perform(admin(delete(ADMIN_PATH + "/" + (deleteFirst ? first : last)))).andExpect(status().isNoContent());
        assertThat(displayOrder(deleteFirst ? last : first)).isZero();
    }

    /**
     * 서로 다른 태그를 동시에 삭제해도 뒤쪽 태그의 순번이 두 번 정확히 당겨지는지 검증한다.
     *
     * @throws Exception 동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void concurrentDeletesShiftRemainingTagTwice() throws Exception {
        String first = create("first", "첫 태그", 1);
        String second = create("second", "두 번째 태그", 2);
        String remaining = create("remaining", "남을 태그", 3);
        race(() -> {
            tagService.delete(Long.parseLong(first));
            return first;
        }, () -> {
            tagService.delete(Long.parseLong(second));
            return second;
        });
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

    /**
     * API를 거치지 않는 직접 저장과 수정에서도 DB가 중복 순번을 거부하는지 검증한다.
     *
     * @throws Exception API 요청 또는 검증 중 오류가 발생한 경우
     */
    @Test
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

    /**
     * 첫 태그는 요청 순번과 관계없이 0으로 등록되는지 검증한다.
     *
     * @param requestedOrder 요청할 순번
     * @throws Exception API 요청 또는 검증 중 오류가 발생한 경우
     */
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
    void firstTagAlwaysStartsAtZero(int requestedOrder) throws Exception {
        String id = create("first", "첫 태그", requestedOrder);
        assertThat(displayOrder(id)).isZero();
    }

    /**
     * 등록 순번을 범위 안으로 보정하고 기존 태그의 상대적인 순서를 유지하는지 검증한다.
     *
     * @param requestedOrder 요청할 순번
     * @param expectedOrder 보정 후 등록할 순번
     * @throws Exception API 요청 또는 검증 중 오류가 발생한 경우
     */
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
    void creationClampsOrderAndKeepsConsecutivePositions(int requestedOrder, int expectedOrder) throws Exception {
        String first = create("first", "첫 태그", 0);
        String second = create("second", "두 번째 태그", 1);
        String third = create("third", "세 번째 태그", 2);

        String inserted = create("inserted", "새 태그", requestedOrder);

        assertThat(displayOrder(inserted)).isEqualTo(expectedOrder);
        assertThat(jdbc.queryForList("SELECT display_order FROM tags ORDER BY display_order", Integer.class))
            .containsExactly(0, 1, 2, 3);
        assertThat(
            jdbc.queryForList("SELECT id FROM tags WHERE id <> ? ORDER BY display_order", String.class, inserted)
        ).containsExactly(first, second, third);
    }

    /**
     * 수정 순번을 범위 안으로 보정하고 이동 대상을 제외한 태그의 상대적인 순서를 유지하는지 검증한다.
     *
     * @param requestedOrder 요청할 순번
     * @param expectedOrder 보정 후 이동할 순번
     * @throws Exception API 요청 또는 검증 중 오류가 발생한 경우
     */
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
    void updateClampsOrderAndKeepsConsecutivePositions(int requestedOrder, int expectedOrder) throws Exception {
        String first = create("first", "첫 태그", 0);
        String moving = create("moving", "이동할 태그", 1);
        String last = create("last", "마지막 태그", 2);

        mvc
            .perform(
                admin(patch(ADMIN_PATH + "/" + moving))
                    .content(mapper.writeValueAsString(new TagUpdateRequest(null, requestedOrder)))
            )
            .andExpect(status().isOk());

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

    /**
     * SQL 와일드카드 문자를 검색어의 일반 문자로 처리하는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void keywordTreatsSqlWildcardsAsLiteralText() throws Exception {
        create("percent", "100%", 1);
        create("other", "100개", 1);
        mvc
            .perform(user(get(LIST_PATH).param("keyword", "%")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.totalElements").value(1))
            .andExpect(jsonPath("$.data.content[0].name").value("100%"));
    }

    /**
     * 허용하지 않은 페이지·크기·정렬 조건을 거부하는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
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

    /**
     * 최대 페이지 번호와 최대 페이지 크기로 조회해도 JPA 조회 위치 범위 오류가 발생하지 않는지 검증한다.
     *
     * @throws Exception API 요청 또는 검증 중 오류가 발생한 경우
     */
    @Test
    void maximumPageNumberIsAccepted() throws Exception {
        mvc
            .perform(user(get(LIST_PATH).param("page", "21474836").param("size", "100")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.content").isEmpty())
            .andExpect(jsonPath("$.data.page").value(21_474_836));
    }

    /**
     * 태그 조회는 인증 사용자에게, 변경은 관리자에게만 허용하는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
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

    /**
     * 유효하지 않은 생성 요청이 태그나 멱등 기록을 남기지 않는지 검증한다.
     *
     * @param body 유효하지 않은 태그 생성 JSON 본문
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @ParameterizedTest
    @ValueSource(
        strings = {
            "{}",
            "{\"name\":\"\",\"displayOrder\":0}",
            "{\"name\":\"  \",\"displayOrder\":0}",
            "{\"name\":\"태그\"}"
        }
    )
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
    void missingBlankAndOversizedKeysAndNamesAreRejected() throws Exception {
        String body = "{\"name\":\"태그\",\"displayOrder\":0}";
        mvc.perform(admin(post(ADMIN_PATH)).content(body)).andExpect(status().isBadRequest());
        for (String key : List.of(" ", "k".repeat(101))) {
            mvc
                .perform(admin(post(ADMIN_PATH)).header("Idempotency-Key", key).content(body))
                .andExpect(status().isBadRequest());
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
    void nonAsciiIdempotencyKeysAreRejected(String key) throws Exception {
        mvc
            .perform(
                admin(post(ADMIN_PATH)).header("Idempotency-Key", key).content("{\"name\":\"태그\",\"displayOrder\":0}")
            )
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error.code").value("COMMON_INVALID_REQUEST"));
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

    /**
     * 빈 수정 요청·공백 이름·없는 태그에 정해진 오류를 반환하는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void emptyOrBlankPatchAndUnknownTagsReturnExpectedErrors() throws Exception {
        String id = create("tag", "해시", 0);
        for (String body : List.of("{}", "{\"name\":\"  \"}", "{\"name\":null,\"displayOrder\":null}")) {
            mvc.perform(admin(patch(ADMIN_PATH + "/" + id)).content(body)).andExpect(status().isBadRequest());
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

    /**
     * 중복 이름으로 생성하거나 수정하면 변경을 롤백하는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
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
     * 태그 이름 변경이나 삭제 이후에도 최초 생성 응답을 반환하는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void replayPreservesOriginalResponseAfterTagUpdateAndDelete() throws Exception {
        String original
            = mvc
                .perform(
                    admin(post(ADMIN_PATH))
                        .header("Idempotency-Key", "original")
                        .content("{\"name\":\"해시\",\"displayOrder\":0}")
                )
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String id = mapper.readTree(original).path("data").path("tagId").asText();
        mvc.perform(admin(patch(ADMIN_PATH + "/" + id)).content("{\"name\":\"새 이름\"}")).andExpect(status().isOk());
        mvc
            .perform(
                admin(post(ADMIN_PATH))
                    .header("Idempotency-Key", "original")
                    .content("{\"displayOrder\":0,\"name\":\"해시\"}")
            )
            .andExpect(status().isCreated())
            .andExpect(content().json(original));
        mvc.perform(admin(delete(ADMIN_PATH + "/" + id))).andExpect(status().isNoContent());
        mvc
            .perform(
                admin(post(ADMIN_PATH))
                    .header("Idempotency-Key", "original")
                    .content("{\"name\":\"해시\",\"displayOrder\":0}")
            )
            .andExpect(status().isCreated())
            .andExpect(content().json(original));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags", Integer.class)).isZero();
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

    /**
     * 사용 중인 태그의 삭제는 거부하고 이름 변경은 허용하는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
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

    /**
     * 동일 이름의 동시 생성 요청 중 하나만 성공하는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void concurrentDuplicateNamesCreateOneTagAndRejectTheOtherRequest() throws Exception {
        List<String> outcomes = race(() -> createOutcome("first"), () -> createOutcome("second"));
        assertThat(outcomes).containsExactlyInAnyOrder("CREATED", "PROBLEM_TAG_DUPLICATED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tag_creation_records", Integer.class)).isEqualTo(1);
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
        assertThat(create("ttl", "스택", 1)).isEqualTo(newId);
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
        create("old", "해시", 0);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-13T01:00:00Z"));
        create("fresh", "스택", 1);
        when(clock.instant()).thenReturn(Instant.parse("2026-10-14T00:00:00Z"));
        cleanupScheduler.deleteExpiredRecords();
        assertThat(jdbc.queryForList("SELECT idempotency_key FROM tag_creation_records", String.class))
            .containsExactly("fresh");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM tags", Integer.class)).isEqualTo(2);
        assertThat(creationRecords.deleteExpired()).isZero();
    }

    /**
     * 만료 정리가 실행마다 최대 500개만 삭제하는지 검증한다.
     */
    @Test
    void cleanupDeletesAtMost500RequestsPerRun() {
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
                    created_at, updated_at, version, result_tag_id, result_name)
            VALUES ('admin', ?, '태그', 1, ?, ?, ?, 1, 1, '태그')
            """, rows);
        assertThat(creationRecords.deleteExpired()).isEqualTo(500);
        assertThat(creationRecords.deleteExpired()).isEqualTo(1);
        assertThat(creationRecords.deleteExpired()).isZero();
    }

    /**
     * 정리가 처리 중인 요청을 건너뛰고 갱신된 기록을 보존하는지 검증한다.
     *
     * @throws Exception 테스트 요청·동시 작업 또는 검증 준비 중 오류가 발생한 경우
     */
    @Test
    void cleanupSkipsLockedRequestAndDoesNotDeleteItsRenewedRecord() throws Exception {
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
                assertThat(creationRecords.deleteExpired()).isEqualTo(1);
            } finally {
                renew.countDown();
            }
            String renewedId = pending.get(10, TimeUnit.SECONDS);
            assertThat(creationRecords.deleteExpired()).isZero();
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
     * @return 성공 시 CREATED, 실패 시 API 응답의 오류 코드 이름
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
        return mapper.readTree(response).path("data").path("tagId").asText();
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
