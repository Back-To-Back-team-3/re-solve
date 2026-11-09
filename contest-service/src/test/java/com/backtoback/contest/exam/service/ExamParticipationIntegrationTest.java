package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.service.EventJsonCodec;
import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.common.web.logging.CorrelationIdFilter;
import com.backtoback.contest.exam.repository.ContestResourceGuardRepository;
import com.backtoback.contest.exam.repository.ContestResourceGuardRepository.Resource;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

import tools.jackson.databind.ObjectMapper;

/** 참가 상태·이력·이벤트와 MySQL 경합을 검증한다. */
@SpringBootTest(
    properties = {
        "spring.cloud.aws.sns.enabled=false",
        "spring.cloud.aws.sqs.enabled=false"
    }
)
@Testcontainers(disabledWithoutDocker = true)
class ExamParticipationIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("contest_db");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    /**
     * 각 테스트 컨텍스트가 격리된 MySQL과 Redis에 연결되도록 주소를 주입한다.
     *
     * @param registry 컨테이너 연결 정보를 등록할 테스트 설정
     */
    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @MockitoBean
    private MemberReplicaService refresh;
    @Autowired
    private ExamParticipationService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TransactionTemplate transactions;
    @Autowired
    private ContestResourceGuardRepository guards;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private EventJsonCodec eventJsonCodec;
    private String examId;

    /** 각 시나리오가 독립된 시험과 회원 복제본으로 시작하도록 데이터를 저장한다. */
    @BeforeEach
    void setup() {
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM exam_participant_histories");
        jdbc.update("DELETE FROM exam_participants");
        jdbc.update("DELETE FROM exam_problems");
        jdbc.update("DELETE FROM exams");
        jdbc.update("DELETE FROM contest_resource_guards");
        jdbc.update("DELETE FROM member_replicas");
        jdbc.update("DELETE FROM study_membership_replicas");
        jdbc
            .update(
                "INSERT INTO member_replicas (member_id,nickname,member_status,source_version,"
                    + "synced_at,created_at,updated_at) " + "VALUES (7,'회원','ACTIVE',1,NOW(6),NOW(6),NOW(6))"
            );
        jdbc
            .update(
                "INSERT INTO study_membership_replicas (study_id,member_id,membership_id,is_active,"
                    + "synced_at,created_at,updated_at) " + "VALUES (12,7,1,true,NOW(6),NOW(6),NOW(6))"
            );
        jdbc
            .update(
                "INSERT INTO exams (study_id,creator_id,title,mode,starts_at,ends_at,status,"
                    + "exam_revision,total_score,"
                    + "result_revision,version,created_at,updated_at) VALUES (12,7,'시험','FIXED',"
                    + "DATE_ADD(NOW(6), INTERVAL 1 HOUR),"
                    + "DATE_ADD(NOW(6), INTERVAL 2 HOUR),'SCHEDULED',1,100,0,0,NOW(6),NOW(6))"
            );
        var now = java.time.LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc.update("UPDATE exams SET starts_at=?, ends_at=?", now.plusHours(1), now.plusHours(2));
        examId = jdbc.queryForObject("SELECT MAX(id) FROM exams", String.class);
        jdbc
            .update(
                "INSERT INTO exam_problems (exam_id,problem_id,problem_revision_id,problem_title,"
                    + "revision_number,score,"
                    + "display_order,created_at,updated_at) VALUES (?,1,10,'문제',1,100,1,NOW(6),NOW(6))",
                examId
            );
        given(refresh.refresh(7)).willReturn("ACTIVE");
    }

    /** 신규 등록만 한도를 적용하고 취소로 비워진 자리를 다시 사용할 수 있게 한다. */
    @Test
    @DisplayName("활성 참가가 한도에 도달하면 기존 등록은 반환하고 신규 등록만 거절한다")
    void enforcesActiveParticipantLimit() {
        var rows
            = IntStream
                .range(0, ExamParticipationService.MAX_ACTIVE_PARTICIPANTS - 1)
                .mapToObj(
                    index -> new Object[] {
                        examId,
                        1000L + index,
                        examId
                    }
                )
                .toList();
        jdbc.batchUpdate("""
            INSERT INTO exam_participants (
                exam_id, member_id, status, registered_at, personal_ends_at,
                total_score, is_ranked, version, created_at, updated_at
            )
            SELECT ?, ?, 'REGISTERED', NOW(6), ends_at, 0, TRUE, 0, NOW(6), NOW(6)
            FROM exams WHERE id = ?
            """, rows);
        jdbc.update("""
            INSERT INTO member_replicas (
                member_id,nickname,member_status,source_version,synced_at,created_at,updated_at
            )
            VALUES (8,'회원','ACTIVE',1,NOW(6),NOW(6),NOW(6))
            """);
        jdbc.update("""
            INSERT INTO study_membership_replicas (
                study_id,member_id,membership_id,is_active,synced_at,created_at,updated_at
            )
            VALUES (12,8,2,TRUE,NOW(6),NOW(6),NOW(6))
            """);
        given(refresh.refresh(8)).willReturn("ACTIVE");

        assertThat(service.register(examId, "7").created()).isTrue();
        assertThat(service.register(examId, "7").created()).isFalse();
        assertThatThrownBy(() -> service.register(examId, "8"))
            .isInstanceOfSatisfying(
                ContestException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(ContestErrorCode.EXAM_PARTICIPANT_LIMIT_REACHED)
            );
        assertThat(count("outbox_events")).isEqualTo(1);
        service.cancel(examId, "7");
        assertThat(service.register(examId, "8").created()).isTrue();
    }

    /** 공통 헤더와 최대 길이의 문제·수신자 필드를 함께 직렬화해 발행 한도를 확인한다. */
    @Test
    @DisplayName("한도까지 등록된 시험의 전체 수신자 스냅샷이 256 KiB 이하다")
    void fullParticipantSnapshotFitsEventLimit() {
        String id = Long.toString(Long.MAX_VALUE);
        String time = "9999-12-31T23:59:59.999999+09:00";
        var metadata
            = new EventMetadata(UUID.randomUUID(), "ExamUpdated", "v1", OffsetDateTime.parse(time), id, "a".repeat(64));
        var payload
            = new ExamManagementService.UpdatedPayload(
                id,
                id,
                Integer.toString(Integer.MAX_VALUE),
                Collections.nCopies(20, new ExamManagementService.UpdatedProblem(id, id, "9999.99", 20)),
                time,
                time,
                Collections.nCopies(ExamParticipationService.MAX_ACTIVE_PARTICIPANTS, id)
            );

        assertThat(eventJsonCodec.serialize(metadata, payload).getBytes(StandardCharsets.UTF_8).length)
            .isLessThanOrEqualTo(256 * 1024);
    }

    /** 취소 후 새 참가를 만들면서 동일 요청의 이력과 Outbox 중복을 확인한다. */
    @Test
    @DisplayName("중복 등록과 취소 재요청은 이벤트를 중복 생성하지 않고 재등록은 새 참가를 생성한다")
    void registrationAndCancellationAreIdempotent() {
        // given
        // when & then: 등록·취소·재등록의 순서와 응답을 함께 검증한다.
        var first = service.register(examId, "7");
        assertThat(first.created()).isTrue();
        assertThat(service.register(examId, "7").created()).isFalse();
        var canceled = service.cancel(examId, "7");
        assertThat(service.cancel(examId, "7")).isEqualTo(canceled);
        var second = service.register(examId, "7");
        assertThat(second.response().participantId()).isNotEqualTo(first.response().participantId());
        assertThat(count("exam_participant_histories")).isEqualTo(3);
        assertThat(count("outbox_events")).isEqualTo(3);
        String payload
            = jdbc
                .queryForObject(
                    "SELECT payload FROM outbox_events "
                        + "WHERE event_type='ExamParticipantRegistered' ORDER BY id LIMIT 1",
                    String.class
                );
        var event = objectMapper.readTree(payload);
        assertThat(event.path("context").asString()).isEqualTo("EXAM");
        assertThat(event.path("examId").asString()).isEqualTo(examId);
        assertThat(event.path("contextId").asString()).isEqualTo(examId);
        assertThat(event.path("participantId").asString()).isEqualTo(first.response().participantId());
        assertThat(event.path("memberId").asString()).isEqualTo("7");
        assertThat(event.path("participantRevision").asString()).isEqualTo("1");
        assertThat(event.path("contextRevision").asString()).isEqualTo("1");
        assertThat(event.path("problemIds").get(0).asString()).isEqualTo("1");
        assertThat(event.path("startsAt").asString()).isNotBlank();
        assertThat(event.path("endsAt").asString()).isNotBlank();
        String cancelPayload
            = jdbc
                .queryForObject(
                    "SELECT payload FROM outbox_events WHERE event_type='ExamParticipantCanceled'",
                    String.class
                );
        var cancelEvent = objectMapper.readTree(cancelPayload);
        assertThat(cancelEvent.path("participantId").asString()).isEqualTo(first.response().participantId());
        assertThat(cancelEvent.path("participantRevision").asString()).isEqualTo("2");
        assertThat(cancelEvent.path("contextRevision").asString()).isEqualTo("1");
        assertThat(cancelEvent.path("canceledAt").asString()).isNotBlank();
    }

    /** 스터디 구성원 복제본 해제 이후에도 등록 당시 자격과 최초 시각이 유지되는지 확인한다. */
    @Test
    @DisplayName("스터디를 탈퇴한 기존 참가자는 입장하며 재입장은 최초 시각과 버전을 유지한다")
    void reentryAfterLeavingStudy() {
        // given
        service.register(examId, "7");
        jdbc.update("UPDATE study_membership_replicas SET is_active=false");
        jdbc
            .update(
                "UPDATE exams SET starts_at=? WHERE id=?",
                java.time.LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).minusMinutes(1),
                examId
            );
        // when
        var entry = service.enter(examId, "7");
        var again = service.enter(examId, "7");
        // then
        assertThat(entry.enteredAt()).isNotNull().isEqualTo(again.enteredAt());
        assertThat(again.participantRevision()).isEqualTo("2");
        assertThat(count("exam_participant_histories")).isEqualTo(2);
    }

    /** 두 트랜잭션을 동시에 시작해 활성 참가와 Outbox의 유일성을 확인한다. */
    @Test
    @DisplayName("동시 등록은 하나의 활성 참가와 등록 이벤트만 생성한다")
    void concurrentRegistration() throws Exception {
        // given
        var start = new CountDownLatch(1);
        // when & then: 두 요청의 잠금 경합과 커밋 결과를 함께 검증한다.
        try (var pool = Executors.newFixedThreadPool(2)) {
            var one = pool.submit(() -> {
                start.await();
                return service.register(examId, "7");
            });
            var two = pool.submit(() -> {
                start.await();
                return service.register(examId, "7");
            });
            start.countDown();
            var first = one.get(10, TimeUnit.SECONDS);
            var second = two.get(10, TimeUnit.SECONDS);
            assertThat(first.created()).isNotEqualTo(second.created());
            assertThat(first.response().participantId()).isEqualTo(second.response().participantId());
        }
        assertThat(count("exam_participants")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
    }

    /** 회원 복제본 정지 상태와 서버 시간 경계에 따른 차단을 확인한다. */
    @Test
    @DisplayName("정지된 회원은 입장하지 못하고 시작 시각 후에는 참가를 취소하지 못한다")
    void suspensionAndCancellationBoundary() {
        // given
        service.register(examId, "7");
        jdbc.update("UPDATE member_replicas SET member_status='SUSPENDED' WHERE member_id=7");
        // when & then
        assertThatThrownBy(() -> service.enter(examId, "7"))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.MEMBER_SUSPENDED)
            );
        assertThatThrownBy(() -> service.cancel(examId, "7"))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.MEMBER_SUSPENDED)
            );
        jdbc.update("UPDATE member_replicas SET member_status='ACTIVE' WHERE member_id=7");
        jdbc
            .update(
                "UPDATE exams SET starts_at=? WHERE id=?",
                java.time.LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).minusMinutes(1),
                examId
            );
        assertThatThrownBy(() -> service.cancel(examId, "7"))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.EXAM_PARTICIPATION_CANCELLATION_CLOSED)
            );
    }

    /** 상관관계 ID 검증 실패를 주입해 참가와 이력의 원자적 롤백을 확인한다. */
    @Test
    @DisplayName("등록 이벤트 저장이 실패하면 참가와 이력도 롤백한다")
    void outboxFailureRollsBack() {
        // given
        MDC.put(CorrelationIdFilter.MDC_KEY, "x".repeat(65));
        // when & then
        try {
            assertThatThrownBy(() -> service.register(examId, "7")).isInstanceOf(IllegalArgumentException.class);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
        // then: 실패한 트랜잭션의 행이 남지 않는다.
        assertThat(count("exam_participants")).isZero();
        assertThat(count("exam_participant_histories")).isZero();
        assertThat(count("outbox_events")).isZero();
    }

    /** 정지 상태에서도 이미 취소된 응답은 외부 조회 없이 그대로 반환한다. */
    @Test
    @DisplayName("정지된 회원의 취소 재요청은 회원 조회 없이 기존 결과를 반환한다")
    void canceledRetrySkipsMemberLookup() {
        // given
        service.register(examId, "7");
        var canceled = service.cancel(examId, "7");
        jdbc.update("UPDATE member_replicas SET member_status='SUSPENDED' WHERE member_id=7");
        reset(refresh);
        // when
        var result = service.cancel(examId, "7");
        // then
        assertThat(result).isEqualTo(canceled);
        verifyNoInteractions(refresh);
        assertThat(count("exam_participant_histories")).isEqualTo(2);
        assertThat(count("outbox_events")).isEqualTo(2);
    }

    /** 원격 조회 중 다른 취소가 커밋되면 재잠금 후 기존 결과를 반환한다. */
    @Test
    @DisplayName("회원 조회 중 다른 취소가 완료되면 이력과 이벤트를 중복 생성하지 않는다")
    void concurrentCancellationWhileMemberLookupWaits() throws Exception {
        // given
        service.register(examId, "7");
        reset(refresh);
        var firstLookup = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        given(refresh.refresh(7)).willAnswer(invocation -> {
            if (calls.getAndIncrement() == 0) {
                firstLookup.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out");
                }
            }
            return "ACTIVE";
        });
        // when & then: 후발 취소가 먼저 커밋되고 선행 요청은 같은 결과를 반환한다.
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> service.cancel(examId, "7"));
            assertThat(firstLookup.await(5, TimeUnit.SECONDS)).isTrue();
            try {
                var second = pool.submit(() -> service.cancel(examId, "7"));
                var committed = second.get(5, TimeUnit.SECONDS);
                release.countDown();
                assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(committed);
            } finally {
                release.countDown();
            }
        }
        assertThat(count("exam_participant_histories")).isEqualTo(2);
        assertThat(count("outbox_events")).isEqualTo(2);
    }

    /** 회원 조회 실패 시 새로운 취소와 그 이력·이벤트는 저장하지 않는다. */
    @Test
    @DisplayName("신규 취소의 회원 조회가 실패하면 503 오류를 반환하고 상태를 보존한다")
    void cancellationMemberLookupFailsClosed() {
        // given
        service.register(examId, "7");
        given(refresh.refresh(7)).willThrow(new ContestException(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE));
        // when & then
        assertThatThrownBy(() -> service.cancel(examId, "7"))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE)
            );
        assertThat(jdbc.queryForObject("SELECT status FROM exam_participants", String.class)).isEqualTo("REGISTERED");
        assertThat(count("exam_participant_histories")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
    }

    /** 취소 이벤트 저장 실패가 상태 전이와 추가 이력을 모두 롤백하는지 확인한다. */
    @Test
    @DisplayName("취소 이벤트 저장이 실패하면 참가 상태와 취소 이력도 롤백한다")
    void cancellationOutboxFailureRollsBack() {
        // given
        service.register(examId, "7");
        MDC.put(CorrelationIdFilter.MDC_KEY, "x".repeat(65));
        // when & then
        try {
            assertThatThrownBy(() -> service.cancel(examId, "7")).isInstanceOf(IllegalArgumentException.class);
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
        // then
        assertThat(jdbc.queryForObject("SELECT status FROM exam_participants", String.class)).isEqualTo("REGISTERED");
        assertThat(count("exam_participant_histories")).isEqualTo(1);
        assertThat(count("outbox_events")).isEqualTo(1);
    }

    /** 가드를 잡은 트랜잭션이 커밋될 때까지 입장이 대기하고 준비된 가드로 거절되는지 확인한다. */
    @Test
    @DisplayName("탈퇴 가드 준비가 먼저 확정되면 대기한 입장을 차단한다")
    void withdrawalGuardWinsEntryRace() throws Exception {
        // given
        service.register(examId, "7");
        jdbc
            .update(
                "UPDATE exams SET starts_at=? WHERE id=?",
                java.time.LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).minusMinutes(1),
                examId
            );
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        // when & then: 두 요청의 잠금 경합과 커밋 결과를 함께 검증한다.
        try (var pool = Executors.newFixedThreadPool(2)) {
            var withdrawal = pool.submit(() -> transactions.executeWithoutResult(status -> {
                guards.lockAll(java.util.List.of(new Resource("MEMBER", 7)));
                jdbc
                    .update(
                        "UPDATE contest_resource_guards SET state='PREPARED' "
                            + "WHERE resource_type='MEMBER' AND resource_id=7"
                    );
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var entry = pool.submit(() -> service.enter(examId, "7"));
            try {
                assertThatThrownBy(() -> entry.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {
                release.countDown();
            }
            withdrawal.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> entry.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ContestException.class)
                .satisfies(
                    error -> assertThat(((ContestException)error.getCause()).getErrorCode())
                        .isEqualTo(ContestErrorCode.CONTEST_OPERATION_BLOCKED)
                );
        }
        assertThat(jdbc.queryForObject("SELECT entered_at FROM exam_participants", java.time.LocalDateTime.class))
            .isNull();
    }

    /** 부모 시험 행 잠금 후 시작 시각 변경이 취소 판정보다 먼저 커밋되는 경합을 확인한다. */
    @Test
    @DisplayName("시험 시작 변경이 부모 잠금을 먼저 잡으면 대기한 취소는 거절한다")
    void startWinsCancellationRace() throws Exception {
        // given
        service.register(examId, "7");
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        // when & then: 두 요청의 잠금 경합과 커밋 결과를 함께 검증한다.
        try (var pool = Executors.newFixedThreadPool(2)) {
            var starting = pool.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc
                    .update(
                        "UPDATE exams SET starts_at=? WHERE id=?",
                        java.time.LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).minusMinutes(1),
                        examId
                    );
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out");
                    }
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(interrupted);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            var cancel = pool.submit(() -> service.cancel(examId, "7"));
            try {
                assertThatThrownBy(() -> cancel.get(200, TimeUnit.MILLISECONDS))
                    .isInstanceOf(java.util.concurrent.TimeoutException.class);
            } finally {
                release.countDown();
            }
            starting.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> cancel.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ContestException.class)
                .satisfies(
                    error -> assertThat(((ContestException)error.getCause()).getErrorCode())
                        .isEqualTo(ContestErrorCode.EXAM_PARTICIPATION_CANCELLATION_CLOSED)
                );
        }
        assertThat(count("outbox_events")).isEqualTo(1);
    }

    /**
     * 테스트 테이블의 현재 행 수를 조회한다.
     *
     * @param table 고정된 테스트 테이블명
     * @return 저장된 행 수
     */
    private int count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }
}
