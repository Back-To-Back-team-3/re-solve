package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.event.service.OutboxRelay;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.dto.request.ProblemCreateRequest;
import com.backtoback.problem.problem.dto.response.ProblemCreateResponse;

/** 실제 MySQL 트랜잭션에서 등록·멱등 경합·감사 및 파일 연결 롤백을 확인한다. */
@MockitoBean(types = OutboxRelay.class)
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
class ProblemCreateServiceTest {
    @Container
    private static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("problem_db");
    @Container
    private static final GenericContainer<?> REDIS
        = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", "test-password");
    @Autowired
    private ProblemCreateService service;
    @MockitoSpyBean
    private ProblemCreateStore store;
    @Autowired
    private ProblemAssetUploadTracking tracking;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoBean
    private ProblemAssetUploadService uploads;
    private final AtomicReference<CyclicBarrier> race = new AtomicReference<>();

    /**
     * 테스트 컨테이너 연결 정보를 제공한다.
     *
     * @param registry 테스트 속성 등록기
     */
    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "test-password");
    }

    /** 외부 S3만 대체하고 파일 추적·완료는 실제 DB에 기록한다. */
    @BeforeEach
    void uploadFixture() {
        race.set(null);
        doAnswer(invocation -> {
            boolean body = invocation.getArgument(3);
            var barrier = race.get();
            if (body && barrier != null) {
                barrier.await(10, TimeUnit.SECONDS);
            }
            var ticket = tracking.start(invocation.getArgument(1), invocation.getArgument(2), body);
            tracking.complete(ticket, "fixed-version");
            return ticket.id();
        }).when(uploads).upload(anyString(), anyLong(), any(), anyBoolean());
    }

    /**
     * 등록은 PRIVATE 첫 회차와 기본 제한·테스트·감사를 저장하고 재요청은 최초 버전을 반환한다.
     */
    @Test
    @DisplayName("등록은 PRIVATE 첫 회차와 기본 제한·테스트·감사를 저장하고 재요청은 최초 버전을 반환한다")
    void registrationAndReplay() {
        String key = UUID.randomUUID().toString();
        var request = ProblemCreateFixtures.request();
        var created = service.create(8001, key, request, "127.0.0.1");
        assertThat(created.revisionNumber()).isEqualTo(1);
        assertThat(created.rowVersion()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT visibility FROM problems WHERE id=?", String.class, created.problemId()))
            .isEqualTo("PRIVATE");
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM problem_revision_tests WHERE problem_revision_id=?",
                    Integer.class,
                    created.problemRevisionId()
                )
        ).isEqualTo(2);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT output_limit_kb FROM problem_revisions WHERE id=?",
                    Integer.class,
                    created.problemRevisionId()
                )
        ).isEqualTo(64);
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE target_id=?", Integer.class, created.problemId())
        ).isEqualTo(1);
        jdbc.update("UPDATE problems SET row_version=2 WHERE id=?", created.problemId());
        clearInvocations(uploads);
        assertThat(service.create(8001, key, request, "127.0.0.1")).isEqualTo(created);
        verifyNoInteractions(uploads);
        var changed
            = new ProblemCreateRequest(
                request.scope(),
                "다른 제목",
                request.description(),
                request.constraints(),
                request.examples(),
                request.difficulty(),
                request.tagIds(),
                request.functionSpec(),
                null,
                request.testCases()
            );
        assertThatThrownBy(() -> service.create(8001, key, changed, "127.0.0.1"))
            .isInstanceOfSatisfying(
                ProblemException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT)
            );
    }

    /**
     * 같은 키의 동시 최초 등록은 한 회차·감사만 커밋하고 동일 응답을 반환한다.
     *
     * @throws Exception 테스트 실행 또는 결과 대기에 실패한 경우
     */
    @Test
    @DisplayName("같은 키의 동시 최초 등록은 한 회차·감사만 커밋하고 동일 응답을 반환한다")
    void concurrentRegistration() throws Exception {
        String key = UUID.randomUUID().toString();
        race.set(new CyclicBarrier(2));
        try (var executor = Executors.newFixedThreadPool(2)) {
            var first = executor.submit(() -> service.create(8002, key, ProblemCreateFixtures.request(), null));
            var second = executor.submit(() -> service.create(8002, key, ProblemCreateFixtures.request(), null));
            var result = first.get(30, TimeUnit.SECONDS);
            assertThat(second.get(30, TimeUnit.SECONDS)).isEqualTo(result);
            assertThat(
                jdbc
                    .queryForObject(
                        "SELECT COUNT(*) FROM problem_revisions WHERE author_id=8002 AND idempotency_key=?",
                        Integer.class,
                        key
                    )
            ).isEqualTo(1);
            assertThat(
                jdbc
                    .queryForObject(
                        "SELECT COUNT(*) FROM audit_logs WHERE target_id=?",
                        Integer.class,
                        result.problemId()
                    )
            ).isEqualTo(1);
            assertThat(
                jdbc
                    .queryForObject(
                        "SELECT COUNT(*) FROM problem_test_assets WHERE owner_id=8002 AND status='LINKED'",
                        Integer.class
                    )
            ).isEqualTo(2);
            assertFileOutcomes(8002);
        }
    }

    /**
     * 업로드 도중 최초 응답이 커밋되면 같은 본문은 재응답하고 다른 본문은 충돌시키며 미참조 파일을 분류한다.
     *
     * @param differentBody 같은 키에 다른 제목을 사용하는지 여부
     * @throws Exception 실행 또는 경합 대기 실패
     */
    @ParameterizedTest
    @ValueSource(
        booleans = {
            false,
            true
        }
    )
    @DisplayName("업로드 중 승자가 확정되어도 패자의 파일만 실패 분류를 남긴다")
    void replayDuringUpload(boolean differentBody) throws Exception {
        long ownerId = differentBody ? 8007 : 8006;
        var uploading = new CountDownLatch(1);
        var committed = new CountDownLatch(1);
        var first = new AtomicBoolean(true);
        doAnswer(invocation -> {
            boolean body = invocation.getArgument(3);
            if (body && first.compareAndSet(true, false)) {
                uploading.countDown();
                if (!committed.await(10, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("최초 응답 커밋 대기 초과");
                }
            }
            var ticket = tracking.start(invocation.getArgument(1), invocation.getArgument(2), body);
            tracking.complete(ticket, "fixed-version");
            return ticket.id();
        }).when(uploads).upload(anyString(), anyLong(), any(), anyBoolean());
        String key = UUID.randomUUID().toString();
        var original = ProblemCreateFixtures.request();
        var candidate
            = new ProblemCreateRequest(
                original.scope(),
                differentBody ? "다른 제목" : original.title(),
                original.description(),
                original.constraints(),
                original.examples(),
                original.difficulty(),
                original.tagIds(),
                original.functionSpec(),
                null,
                original.testCases()
            );
        try (var executor = Executors.newSingleThreadExecutor()) {
            var loser = executor.submit(() -> service.create(ownerId, key, candidate, null));
            com.backtoback.problem.problem.dto.response.ProblemCreateResponse winner;
            try {
                assertThat(uploading.await(10, TimeUnit.SECONDS)).isTrue();
                winner = service.create(ownerId, key, original, null);
            } finally {
                committed.countDown();
            }
            if (differentBody) {
                assertThatThrownBy(() -> loser.get(30, TimeUnit.SECONDS))
                    .cause()
                    .isInstanceOfSatisfying(
                        ProblemException.class,
                        failure -> assertThat(failure.getErrorCode())
                            .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_CONFLICT)
                    );
            } else {
                assertThat(loser.get(30, TimeUnit.SECONDS)).isEqualTo(winner);
            }
            assertFileOutcomes(ownerId);
        }
    }

    /**
     * 경합에서 진 요청의 미참조 파일만 실패 분류가 남고 승자의 참조 파일은 보존되는지 확인한다.
     *
     * @param ownerId 경합한 작성 관리자
     */
    private void assertFileOutcomes(long ownerId) {
        for (String table : java.util.List.of("problem_body_assets", "problem_test_assets")) {
            int count = table.equals("problem_body_assets") ? 1 : 2;
            assertThat(
                jdbc
                    .queryForObject(
                        "SELECT COUNT(*) FROM " + table
                            + " WHERE owner_id=? AND status='PENDING' AND problem_id IS NULL"
                            + " AND uploaded_at IS NOT NULL AND upload_finished_at IS NOT NULL"
                            + " AND failure_code='REGISTRATION_NOT_COMMITTED'",
                        Integer.class,
                        ownerId
                    )
            ).isEqualTo(count);
            assertThat(
                jdbc
                    .queryForObject(
                        "SELECT COUNT(*) FROM " + table
                            + " WHERE owner_id=? AND status='LINKED' AND problem_id IS NOT NULL"
                            + " AND uploaded_at IS NOT NULL AND upload_finished_at IS NOT NULL"
                            + " AND failure_code IS NULL",
                        Integer.class,
                        ownerId
                    )
            ).isEqualTo(count);
        }
    }

    /** 저장과 재조회가 모두 실패해도 원인과 업로드 파일의 실패 분류를 보존한다. */
    @Test
    @DisplayName("재조회 장애를 원래 저장 예외에 남기고 미연결 파일을 분류한다")
    void replayFailurePreservesSaveFailure() {
        var saveFailure = new IllegalStateException("저장 실패");
        var replayFailure = new IllegalStateException("재조회 실패");
        doReturn(Optional.empty()).doThrow(replayFailure).when(store).replay(eq(8010L), eq("replay-fail"), anyString());
        doThrow(saveFailure).when(store).save(any(), any(), any());
        assertThatThrownBy(() -> service.create(8010, "replay-fail", ProblemCreateFixtures.request(), null))
            .isSameAs(saveFailure);
        assertThat(saveFailure.getSuppressed()).containsExactly(replayFailure);
        assertThat(
            jdbc.queryForObject("SELECT failure_code FROM problem_body_assets WHERE owner_id=8010", String.class)
        ).isEqualTo("REGISTRATION_NOT_COMMITTED");
    }

    /** 멱등 결과가 확정된 뒤의 추적 기록 실패는 최초 응답을 바꾸지 않는다. */
    @Test
    @DisplayName("미연결 파일 분류가 실패해도 재조회한 최초 응답을 반환한다")
    void trackingFailureAfterReplay() {
        var response = new ProblemCreateResponse("1", "2", 1, 1);
        doReturn(Optional.empty())
            .doReturn(Optional.of(response))
            .when(store)
            .replay(eq(8011L), eq("replay-ok"), anyString());
        doThrow(new IllegalStateException("저장 실패")).when(store).save(any(), any(), any());
        doThrow(new IllegalStateException("분류 실패")).when(store).recordLinkFailure(any());
        assertThat(service.create(8011, "replay-ok", ProblemCreateFixtures.request(), null)).isEqualTo(response);
        verify(store).recordLinkFailure(any());
    }

    /**
     * 업로드 도중 실패하면 완료된 파일만 분류하고 문제와 감사 기록을 생성하지 않는다.
     *
     * @param completedUploads 실패 전에 완료할 파일 수
     */
    @ParameterizedTest
    @ValueSource(
        ints = {
            0,
            1,
            2
        }
    )
    @DisplayName("업로드 도중 실패하면 완료된 본문과 테스트 파일을 미등록으로 분류한다")
    void testUploadFailureSkipsRegistration(int completedUploads) {
        long authorId = 9100L + completedUploads;
        var count = new AtomicInteger();
        doAnswer(invocation -> {
            if (count.getAndIncrement() == completedUploads) {
                throw new IllegalStateException("파일 업로드 실패");
            }
            boolean body = invocation.getArgument(3);
            var ticket = tracking.start(invocation.getArgument(1), invocation.getArgument(2), body);
            tracking.complete(ticket, "fixed-version");
            return ticket.id();
        }).when(uploads).upload(anyString(), eq(authorId), any(), anyBoolean());
        assertThatThrownBy(() -> service.create(authorId, "upload-fail", ProblemCreateFixtures.request(), null))
            .isInstanceOfSatisfying(
                ProblemException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE)
            );
        verify(store, never()).save(any(), any(), any());
        if (completedUploads == 0) {
            verify(store, never()).recordLinkFailure(any());
        } else {
            verify(store).recordLinkFailure(any());
        }
        assertThat(jdbc.queryForList("""
            SELECT failure_code FROM problem_body_assets WHERE owner_id=? AND uploaded_at IS NOT NULL
            """, String.class, authorId))
            .containsExactlyElementsOf(
                java.util.Collections.nCopies(Math.min(1, completedUploads), "REGISTRATION_NOT_COMMITTED")
            );
        assertThat(jdbc.queryForList("""
            SELECT failure_code FROM problem_test_assets WHERE owner_id=? AND uploaded_at IS NOT NULL
            """, String.class, authorId))
            .containsExactlyElementsOf(
                java.util.Collections.nCopies(Math.max(0, completedUploads - 1), "REGISTRATION_NOT_COMMITTED")
            );
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM problem_revisions WHERE author_id=?", Integer.class, authorId)
        ).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE actor_id=?", Integer.class, authorId))
            .isZero();
    }

    /**
     * 최종 테스트 연결이 실패하면 회차·본문 연결·감사는 롤백하고 업로드 기록은 보존한다.
     */
    @Test
    @DisplayName("최종 테스트 연결이 실패하면 회차·본문 연결·감사는 롤백하고 업로드 기록은 보존한다")
    void failedLinkRollsBack() {
        doAnswer(invocation -> {
            boolean body = invocation.getArgument(3);
            var ticket = tracking.start(invocation.getArgument(1), invocation.getArgument(2), body);
            tracking.complete(ticket, "fixed-version");
            if (!body) {
                jdbc.update("UPDATE problem_test_assets SET status='DELETING' WHERE id=?", ticket.id());
            }
            return ticket.id();
        }).when(uploads).upload(anyString(), anyLong(), any(), anyBoolean());
        String key = UUID.randomUUID().toString();
        assertThatThrownBy(() -> service.create(8003, key, ProblemCreateFixtures.request(), null))
            .isInstanceOf(ProblemException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM problem_revisions WHERE author_id=8003", Integer.class))
            .isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_logs WHERE actor_id=8003", Integer.class)).isZero();
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM problem_body_assets WHERE owner_id=8003 AND status='PENDING' "
                        + "AND uploaded_at IS NOT NULL AND problem_id IS NULL",
                    Integer.class
                )
        ).isEqualTo(1);
        assertThat(
            jdbc.queryForObject("SELECT failure_code FROM problem_body_assets WHERE owner_id=8003", String.class)
        ).isEqualTo("REGISTRATION_NOT_COMMITTED");
    }
}
