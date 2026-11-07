package com.backtoback.contest.exam.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.event.dto.EventMetadata;
import com.backtoback.common.event.service.EventConsumer;
import com.backtoback.common.event.service.EventJsonCodec;
import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.client.ExamContentClient;
import com.backtoback.contest.exam.client.ExamReceiptClient.Receipt;
import com.backtoback.contest.exam.service.ExamDraftStore.SaveRequest;
import com.backtoback.contest.exam.service.ExamSubmissionService.Request;
import com.backtoback.contest.global.error.ContestErrorCode;
import com.backtoback.contest.global.error.ContestException;

/** MySQL 잠금과 Redis 버퍼로 Draft 순번과 재접속 복구를 확인한다. */
@SpringBootTest(
    properties = {
        "spring.cloud.aws.sns.enabled=false",
        "spring.cloud.aws.sqs.enabled=false"
    }
)
@Testcontainers(disabledWithoutDocker = true)
class ExamDraftIntegrationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("contest_db");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    /** 테스트 컨텍스트의 격리된 저장소 주소를 등록한다. */
    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @MockitoBean
    private ExamLifecycleScheduler scheduler;
    @MockitoBean
    private ExamAutoSubmissionScheduler autoScheduler;
    @MockitoBean
    private ExamResultFinalizationScheduler finalizationScheduler;
    @MockitoBean
    private ExamRankingCacheRebuildScheduler rankingCacheScheduler;
    @MockitoBean
    private MemberReplicaService refresh;
    @MockitoBean
    private ExamContentClient content;
    @MockitoBean
    private ExamSubmissionRateLimiter limiter;
    @MockitoSpyBean
    private OutboxEventWriter outbox;
    @Autowired
    private ExamDraftStore store;
    @Autowired
    private ExamDraftBuffer buffer;
    @Autowired
    private ExamSubmissionService submissions;
    @Autowired
    private ExamAutoSubmissionService autoSubmissions;
    @Autowired
    private EventConsumer consumer;
    @Autowired
    private EventJsonCodec codec;
    @Autowired
    private ExamReceiptReconciliation reconciliation;
    @Autowired
    private ExamJudgementService judgements;
    @Autowired
    private ExamEventSnapshotService eventSnapshots;
    @Autowired
    private ExamSubmissionHistoryService submissionHistory;
    @Autowired
    private ExamFinalizationService finalization;
    @Autowired
    private ExamResultQueryService results;
    @Autowired
    private ExamRankingCache rankingCache;
    @Autowired
    private ExamSchedulerCursorStore schedulerCursors;
    @Autowired
    private JdbcTemplate jdbc;
    @MockitoSpyBean
    private StringRedisTemplate redis;
    @Autowired
    private TransactionTemplate transactions;
    private String examId;
    private long participantId;

    /** 입장한 참가자와 두 문제를 저장한다. */
    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM result_snapshots");
        jdbc.update("DELETE FROM contest_admin_operations");
        jdbc.update("DELETE FROM exam_problem_results");
        jdbc.update("DELETE FROM exam_submissions");
        jdbc.update("DELETE FROM exam_drafts");
        jdbc.update("DELETE FROM exam_participant_histories");
        jdbc.update("DELETE FROM exam_participants");
        jdbc.update("DELETE FROM exam_problems");
        jdbc.update("DELETE FROM exams");
        jdbc.update("DELETE FROM member_replicas");
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc
            .update(
                "INSERT INTO member_replicas (member_id,nickname,member_status,source_version,"
                    + "synced_at,created_at,updated_at) " + "VALUES (7,'회원','ACTIVE',1,?,?,?)",
                now,
                now,
                now
            );
        jdbc
            .update(
                "INSERT INTO exams (study_id,creator_id,title,mode,starts_at,ends_at,status,exam_revision,"
                    + "total_score,result_revision,version,created_at,updated_at) VALUES "
                    + "(12,7,'시험','FIXED',?,?,'IN_PROGRESS',1,100,0,0,?,?)",
                now.minusHours(1),
                now.plusHours(1),
                now,
                now
            );
        examId = jdbc.queryForObject("SELECT MAX(id) FROM exams", String.class);
        jdbc
            .update(
                "INSERT INTO exam_participants (exam_id,member_id,status,participant_revision,registered_at,entered_at,"
                    + "personal_ends_at,total_score,is_ranked,version,created_at,updated_at) "
                    + "VALUES (?,7,'STARTED',2,?,?,?,0,false,0,?,?)",
                examId,
                now.minusHours(2),
                now.minusMinutes(30),
                now.plusHours(1),
                now,
                now
            );
        participantId = jdbc.queryForObject("SELECT MAX(id) FROM exam_participants", Long.class);
        for (int problem = 101; problem <= 102; problem++) {
            jdbc
                .update(
                    "INSERT INTO exam_problems (exam_id,problem_id,problem_revision_id,problem_title,revision_number,"
                        + "score,display_order,created_at,updated_at) VALUES (?,?,?,'문제',1,50,?,?,?)",
                    examId,
                    problem,
                    problem + 900,
                    problem - 100,
                    now,
                    now
                );
        }
        given(refresh.refresh(7)).willReturn("ACTIVE");
    }

    /** 언어 전환 이후에도 순번은 문제 전체에서 비교하고 빈 최신 코드를 복구한다. */
    @Test
    @DisplayName("언어를 바꿔도 작은 순번을 무시하고 빈 최신 코드를 복구한다")
    void sequenceAcrossLanguages() {
        // given
        var first = store.prepare(examId, "7", "101", new SaveRequest("java", "코드", "5"));
        var older = store.prepare(examId, "7", "101", new SaveRequest("cpp", "이전", "4"));
        var equal = store.prepare(examId, "7", "101", new SaveRequest("python3", "동일", "5"));
        var empty = store.prepare(examId, "7", "101", new SaveRequest("cpp", "", "6"));
        // when
        assertThat(store.save(first).applied()).isTrue();
        var ignored = store.save(older);
        assertThat(store.save(equal).applied()).isFalse();
        assertThat(store.save(empty).applied()).isTrue();
        jdbc.update("UPDATE exam_participants SET status='FINISHED' WHERE id=?", participantId);
        var recovered = store.read(examId, "7");
        // then
        assertThat(ignored.applied()).isFalse();
        assertThat(ignored.serverSeq()).isEqualTo("5");
        assertThat(recovered.content()).extracting("sourceCode").containsExactlyInAnyOrder("코드", "");
        assertThat(recovered.problemSequences()).extracting("serverSeq").containsExactly("6", "0");
    }

    /** 전송 크기 초과로 저장을 거절해도 기존 Draft와 순번이 유지된다. */
    @Test
    @DisplayName("이벤트 크기를 초과한 신규 Draft는 기존 저장본을 보존한다")
    void oversizedEventPreservesDraft() {
        // given
        var first = store.prepare(examId, "7", "101", new SaveRequest("java", "기존", "1"));
        var oversized
            = store.prepare(examId, "7", "101", new SaveRequest("java", String.valueOf((char)1).repeat(50000), "2"));
        assertThat(store.save(first).applied()).isTrue();
        // when & then
        assertThatThrownBy(() -> store.save(oversized))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode()).isEqualTo(ContestErrorCode.CODE_TOO_LARGE)
            );
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT source_code FROM exam_drafts WHERE exam_participant_id=?",
                    String.class,
                    participantId
                )
        ).isEqualTo("기존");
        assertThat(
            jdbc.queryForObject("SELECT seq FROM exam_drafts WHERE exam_participant_id=?", Long.class, participantId)
        ).isEqualTo(1L);
    }

    /** 접수 뒤 마감과 정지 상태가 바뀌어도 같은 본문은 원래 접수를 반환한다. */
    @Test
    @DisplayName("같은 접수 재요청은 종료와 정지 후에도 중복 저장하지 않는다")
    void submissionIsIdempotentAfterFinish() {
        // given
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        var request = new Request("101", "java", "코드", "1");
        var first = submissions.submit(examId, "7", request);
        jdbc.update("UPDATE exam_participants SET status='FINISHED' WHERE id=?", participantId);
        jdbc.update("UPDATE member_replicas SET member_status='SUSPENDED' WHERE member_id=7");
        // when
        var retry = submissions.submit(examId, "7", request);
        // then
        assertThat(retry.examSubmissionId()).isEqualTo(first.examSubmissionId());
        assertThat(retry.receivedAt()).isEqualTo(first.receivedAt());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        verify(limiter, times(1)).acquire(7, 101);
        assertThatThrownBy(() -> submissions.submit(examId, "7", new Request("101", "java", "다른 코드", "1")))
            .isInstanceOf(ContestException.class);
    }

    /** 같은 순번의 다른 Draft는 접수와 Outbox를 만들지 않는다. */
    @Test
    @DisplayName("최신 Draft와 다른 동일 순번 제출은 거절한다")
    void rejectsConflictingDraft() {
        // given
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        store.save(store.prepare(examId, "7", "101", new SaveRequest("java", "기존", "1")));
        // when & then
        assertThatThrownBy(() -> submissions.submit(examId, "7", new Request("101", "java", "변경", "1")))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.DRAFT_SEQ_CONFLICT)
            );
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
    }

    /** Outbox 실패 시 새 Draft와 접수를 같은 트랜잭션에서 롤백한다. */
    @Test
    @DisplayName("Outbox 저장 실패는 직접 제출과 Draft를 모두 롤백한다")
    void rollsBackSubmissionWhenOutboxFails() {
        // given
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        doThrow(new IllegalStateException("outbox failure"))
            .when(outbox)
            .append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        // when & then
        assertThatThrownBy(() -> submissions.submit(examId, "7", new Request("101", "java", "코드", "1")))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_drafts", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
    }

    /** 같은 순번의 동시 접수는 참가자 잠금과 유일 제약으로 한 건만 저장한다. */
    @Test
    @DisplayName("동시 동일 제출은 하나의 접수와 이벤트를 반환한다")
    void concurrentDuplicateSubmissions() throws Exception {
        // given
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        var request = new Request("101", "java", "코드", "1");
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                return submissions.submit(examId, "7", request);
            });
            var second = pool.submit(() -> {
                start.await();
                return submissions.submit(examId, "7", request);
            });
            // when
            start.countDown();
            var firstResult = first.get(10, TimeUnit.SECONDS);
            var secondResult = second.get(10, TimeUnit.SECONDS);
            // then
            assertThat(secondResult.examSubmissionId()).isEqualTo(firstResult.examSubmissionId());
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions", Integer.class)).isEqualTo(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
        }
    }

    /** 크기 제한 도입 전 접수는 새 제한이나 외부 조회 없이 기존 결과를 반환한다. */
    @Test
    @DisplayName("직렬화 크기가 큰 기존 접수도 동일 본문이면 다시 반환한다")
    void retriesPreviouslyAcceptedLargeEvent() {
        // given
        var code = String.valueOf((char)1).repeat(50000);
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc
            .update(
                "INSERT INTO exam_submissions (exam_participant_id,exam_id,problem_id,draft_seq,language,"
                    + "source_code,submission_type,received_at,status,is_zero_confirmed,created_at,updated_at) "
                    + "VALUES (?,?,101,1,'JAVA',?,'MANUAL',?,'REQUESTED',false,?,?)",
                participantId,
                examId,
                code,
                now,
                now,
                now
            );
        // when
        var result = submissions.submit(examId, "7", new Request("101", "java", code, "1"));
        // then
        assertThat(result.status()).isEqualTo("REQUESTED");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions", Integer.class)).isEqualTo(1);
        org.mockito.Mockito.verifyNoInteractions(content, limiter);
    }

    /** 최신 언어의 빈 코드와 이미 제출한 순번은 자동 접수하지 않는다. */
    @Test
    @DisplayName("수동 종료는 최신 Draft만 자동 제출하고 반복 요청에도 중복하지 않는다")
    void manualFinishSubmitsLatestDraftOnce() {
        // given
        store.save(store.prepare(examId, "7", "101", new SaveRequest("java", "이전", "1")));
        store.save(store.prepare(examId, "7", "101", new SaveRequest("cpp", "최신", "2")));
        store.save(store.prepare(examId, "7", "102", new SaveRequest("java", "", "1")));
        // when
        var result = autoSubmissions.finish(examId, "7");
        var retry = autoSubmissions.finish(examId, "7");
        // then
        assertThat(result.status()).isEqualTo("FINISHED");
        assertThat(result.autoSubmissionIds()).hasSize(1).isEqualTo(retry.autoSubmissionIds());
        assertThat(jdbc.queryForObject("SELECT source_code FROM exam_submissions", String.class)).isEqualTo("최신");
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT received_at=personal_ends_at FROM exam_submissions s "
                        + "JOIN exam_participants p ON p.id=s.exam_participant_id",
                    Boolean.class
                )
        ).isTrue();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
    }

    /** 기존에 허용된 초과 Draft는 종료 전체가 롤백되며 원문을 보존한다. */
    @Test
    @DisplayName("이벤트 크기를 초과한 기존 Draft는 자동 제출과 종료를 롤백한다")
    void oversizedExistingDraftRollsBackFinish() {
        // given
        store.save(store.prepare(examId, "7", "101", new SaveRequest("java", "정상 코드", "1")));
        var code = String.valueOf((char)1).repeat(50000);
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc
            .update(
                "INSERT INTO exam_drafts (exam_participant_id,problem_id,language,source_code,seq,saved_at,"
                    + "version,created_at,updated_at) VALUES (?,102,'JAVA',?,1,?,0,?,?)",
                participantId,
                code,
                now,
                now,
                now
            );
        var before
            = jdbc
                .queryForObject(
                    "SELECT personal_ends_at FROM exam_participants WHERE id=?",
                    LocalDateTime.class,
                    participantId
                );
        // when & then
        assertThatThrownBy(() -> autoSubmissions.finish(examId, "7"))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode()).isEqualTo(ContestErrorCode.CODE_TOO_LARGE)
            );
        assertThat(jdbc.queryForObject("SELECT status FROM exam_participants WHERE id=?", String.class, participantId))
            .isEqualTo("STARTED");
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT personal_ends_at FROM exam_participants WHERE id=?",
                    LocalDateTime.class,
                    participantId
                )
        ).isEqualTo(before);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT source_code FROM exam_drafts WHERE exam_participant_id=? AND problem_id=102",
                    String.class,
                    participantId
                )
        ).isEqualTo(code);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
    }

    /** 3초 지연은 스케줄 실행 조건이며 개인 마감을 변경하지 않는다. */
    @Test
    @DisplayName("자동 제출은 개인 마감 3초 뒤에만 실행한다")
    void automaticSubmissionWaitsThreeSeconds() {
        // given
        store.save(store.prepare(examId, "7", "101", new SaveRequest("java", "코드", "1")));
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc.update("UPDATE exam_participants SET personal_ends_at=? WHERE id=?", now.minusSeconds(1), participantId);
        // when
        autoSubmissions.finishDue(Long.parseLong(examId), 7, participantId);
        // then
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions", Integer.class)).isZero();
        var deadline = now.minusSeconds(4);
        jdbc.update("UPDATE exam_participants SET personal_ends_at=? WHERE id=?", deadline, participantId);
        autoSubmissions.finishDue(Long.parseLong(examId), 7, participantId);
        autoSubmissions.finishDue(Long.parseLong(examId), 7, participantId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT received_at FROM exam_submissions", LocalDateTime.class))
            .isEqualTo(deadline.truncatedTo(java.time.temporal.ChronoUnit.MICROS));
    }

    /** 직접 제출과 수동 종료가 경합해도 같은 순번은 한 번만 접수한다. */
    @Test
    @DisplayName("직접 제출과 수동 종료가 경합하면 동일 Draft를 중복 접수하지 않는다")
    void concurrentSubmissionAndFinish() throws Exception {
        // given
        store.save(store.prepare(examId, "7", "101", new SaveRequest("java", "코드", "1")));
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var manual = pool.submit(() -> {
                start.await();
                try {
                    return submissions.submit(examId, "7", new Request("101", "java", "코드", "1"));
                } catch (ContestException failure) {
                    assertThat(failure.getErrorCode()).isEqualTo(ContestErrorCode.EXAM_ALREADY_FINISHED);
                    return null;
                }
            });
            var finish = pool.submit(() -> {
                start.await();
                return autoSubmissions.finish(examId, "7");
            });
            // when
            start.countDown();
            manual.get(10, TimeUnit.SECONDS);
            finish.get(10, TimeUnit.SECONDS);
        }
        // then
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_submissions", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
    }

    /** 소비 기록과 점수가 함께 저장되며 같은 회차 상충은 격리된다. */
    @Test
    @DisplayName("채점 결과는 절사해 반영하고 같은 회차의 다른 판정은 거절한다")
    void consumesJudgementAndRejectsConflict() {
        // given
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        var receipt = submissions.submit(examId, "7", new Request("101", "java", "코드", "1"));
        var data = new java.util.HashMap<String, Object>();
        data.put("context", "EXAM");
        data.put("examId", examId);
        data.put("examSubmissionId", receipt.examSubmissionId());
        data.put("submissionId", "9001");
        data.put("userId", "7");
        data.put("problemId", "101");
        data.put("problemRevisionId", "1001");
        data.put("judgeAttempt", 1);
        data.put("status", "COMPLETED");
        data.put("verdict", "WA");
        data.put("passedCount", 1);
        data.put("totalCount", 3);
        data.put("submittedAt", receipt.receivedAt().toString());
        var metadata
            = new EventMetadata(
                java.util.UUID.randomUUID(),
                "ExamSubmissionJudged",
                "v1",
                java.time.OffsetDateTime.now(),
                "9001",
                "test"
            );
        var message = codec.serialize(metadata, data);
        var acknowledgements = new java.util.concurrent.atomic.AtomicInteger();
        // when
        assertThatThrownBy(() -> consumer.consume(message, () -> {
            acknowledgements.incrementAndGet();
            throw new IllegalStateException("ack failure");
        })).isInstanceOf(IllegalStateException.class);
        consumer.consume(message, acknowledgements::incrementAndGet);
        // then
        assertThat(acknowledgements).hasValue(2);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT score FROM exam_submissions WHERE id=?",
                    java.math.BigDecimal.class,
                    receipt.examSubmissionId()
                )
        ).isEqualByComparingTo("16.66");
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT total_score FROM exam_participants WHERE id=?",
                    java.math.BigDecimal.class,
                    participantId
                )
        ).isEqualByComparingTo("16.66");
        data.put("verdict", "AC");
        var conflicting
            = new EventMetadata(
                java.util.UUID.randomUUID(),
                "ExamSubmissionJudged",
                "v1",
                java.time.OffsetDateTime.now(),
                "9001",
                "test"
            );
        assertThatThrownBy(
            () -> consumer.consume(codec.serialize(conflicting, data), acknowledgements::incrementAndGet)
        ).isInstanceOf(IllegalArgumentException.class);
        assertThat(acknowledgements).hasValue(2);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT COUNT(*) FROM processed_events WHERE event_id=?",
                    Integer.class,
                    conflicting.eventId().toString()
                )
        ).isZero();
    }

    /** 미종결의 최신 접수가 이전 제출의 점수를 대체하지 않도록 0으로 집계한다. */
    @Test
    @DisplayName("새 제출이 미종결이면 이전 AC 점수를 마지막 제출 점수로 사용하지 않는다")
    void pendingLatestDoesNotReusePreviousScore() {
        // given
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        var first = submissions.submit(examId, "7", new Request("101", "java", "첫 코드", "1"));
        transactions
            .executeWithoutResult(
                status -> judgements
                    .apply(
                        new ExamJudgementService.Result(
                            Long.parseLong(examId),
                            Long.parseLong(first.examSubmissionId()),
                            9001,
                            7,
                            101,
                            1001,
                            1,
                            "JUDGED",
                            "AC",
                            3,
                            3,
                            first.receivedAt()
                        )
                    )
            );
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT total_score FROM exam_participants WHERE id=?",
                    java.math.BigDecimal.class,
                    participantId
                )
        ).isEqualByComparingTo("50.00");
        // when
        submissions.submit(examId, "7", new Request("101", "java", "두 번째 코드", "2"));
        // then
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT total_score FROM exam_participants WHERE id=?",
                    java.math.BigDecimal.class,
                    participantId
                )
        ).isEqualByComparingTo("0.00");
    }

    /** Judge 미접수는 원래 Outbox 이벤트 ID를 유지한 채 재발행 대기로 바꾼다. */
    @Test
    @DisplayName("Judge에 없는 접수는 기존 Outbox를 같은 이벤트 ID로 재전달한다")
    void requeuesOriginalOutbox() {
        // given
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        var receipt = submissions.submit(examId, "7", new Request("101", "java", "코드", "1"));
        var eventId = jdbc.queryForObject("SELECT event_id FROM outbox_events", String.class);
        jdbc.update("UPDATE outbox_events SET status='PUBLISHED'");
        var missing = new Receipt(false, null, null, null, null, null, null, null, null, null, null, null, null, null);
        // when
        reconciliation.reconcile(Long.parseLong(receipt.examSubmissionId()), missing);
        // then
        assertThat(jdbc.queryForObject("SELECT status FROM outbox_events", String.class)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("SELECT event_id FROM outbox_events", String.class)).isEqualTo(eventId);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isEqualTo(1);
    }

    /** 관리자 작업이 허용한 새 회차를 반영한 뒤 오래된 결과를 무시한다. */
    @Test
    @DisplayName("재처리 목표 회차 뒤에 온 이전 결과는 점수를 되돌리지 않는다")
    void rejectsOldJudgementAfterRetry() {
        // given
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        var receipt = submissions.submit(examId, "7", new Request("101", "java", "코드", "1"));
        long id = Long.parseLong(receipt.examSubmissionId());
        var first
            = new ExamJudgementService.Result(
                Long.parseLong(examId),
                id,
                9001,
                7,
                101,
                1001,
                1,
                "FAILED",
                null,
                null,
                null,
                receipt.receivedAt()
            );
        transactions.executeWithoutResult(status -> judgements.apply(first));
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc
            .update(
                "INSERT INTO contest_admin_operations (operation_id,operation_type,context,context_id,receipt_id,"
                    + "request_hash,expected_judge_attempt,target_judge_attempt,state,actor_member_id,reason,"
                    + "created_at,updated_at) "
                    + "VALUES ('retry-test','EXAM_RETRY','EXAM',?,?,?,1,2,'SUCCEEDED',7,'재처리',?,?)",
                examId,
                id,
                "a".repeat(64),
                now,
                now
            );
        var second
            = new ExamJudgementService.Result(
                Long.parseLong(examId),
                id,
                9001,
                7,
                101,
                1001,
                2,
                "JUDGED",
                "CE",
                0,
                3,
                receipt.receivedAt()
            );
        // when
        transactions.executeWithoutResult(status -> judgements.apply(second));
        transactions.executeWithoutResult(status -> judgements.apply(first));
        // then
        assertThat(jdbc.queryForObject("SELECT judge_attempt FROM exam_submissions WHERE id=?", Integer.class, id))
            .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT score FROM exam_submissions WHERE id=?", java.math.BigDecimal.class, id))
            .isEqualByComparingTo("0.00");
    }

    /** Judge 응답의 원래 회원이 다르면 접수 상태를 변경하지 않는다. */
    @Test
    @DisplayName("대사 응답의 회원 연결이 다르면 기존 접수를 보존한다")
    void rejectsMismatchedRemoteReceipt() {
        // given
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        var receipt = submissions.submit(examId, "7", new Request("101", "java", "코드", "1"));
        var remote
            = new Receipt(
                true,
                "9001",
                "EXAM",
                examId,
                receipt.examSubmissionId(),
                "8",
                "101",
                "1001",
                receipt.receivedAt(),
                "COMPLETED",
                1,
                "AC",
                3,
                3
            );
        // when & then
        assertThatThrownBy(() -> reconciliation.reconcile(Long.parseLong(receipt.examSubmissionId()), remote))
            .isInstanceOf(IllegalArgumentException.class);
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT status FROM exam_submissions WHERE id=?",
                    String.class,
                    receipt.examSubmissionId()
                )
        ).isEqualTo("ACCEPTED");
    }

    /** 개인 종료만으로 SSE에 채점 진행률을 노출하지 않는다. */
    @Test
    @DisplayName("전체 종료 전 SSE에는 본인 제출 진행률을 공개하지 않는다")
    void hidesEventProgressBeforeExamClose() {
        // given
        jdbc.update("UPDATE exam_participants SET status='FINISHED' WHERE id=?", participantId);
        // when
        var hidden = eventSnapshots.read(examId, "7");
        // then
        assertThat(hidden.terminalCount()).isNull();
        assertThat(hidden.totalSubmissionCount()).isNull();
        jdbc.update("UPDATE exams SET status='CLOSED' WHERE id=?", examId);
        var visible = eventSnapshots.read(examId, "7");
        assertThat(visible.terminalCount()).isZero();
        assertThat(visible.totalSubmissionCount()).isZero();
    }

    /** 전체 종료 전 판정을 숨기고 변경된 결과 버전으로 이전 커서를 무효화한다. */
    @Test
    @DisplayName("제출 이력은 종료 전 판정을 숨기고 결과 변경 시 커서를 거절한다")
    void submissionHistoryHidesResultsAndExpiresCursor() {
        // given
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        var first = submissions.submit(examId, "7", new Request("101", "java", "첫 코드", "1"));
        transactions
            .executeWithoutResult(
                status -> judgements
                    .apply(
                        new ExamJudgementService.Result(
                            Long.parseLong(examId),
                            Long.parseLong(first.examSubmissionId()),
                            9001,
                            7,
                            101,
                            1001,
                            1,
                            "JUDGED",
                            "AC",
                            3,
                            3,
                            first.receivedAt()
                        )
                    )
            );
        submissions.submit(examId, "7", new Request("101", "java", "두 번째 코드", "2"));
        // when
        var page = submissionHistory.read(examId, "7", null, null, 1);
        var secondPage = submissionHistory.read(examId, "7", null, page.nextCursor(), 1);
        // then
        assertThat(secondPage.items()).singleElement().satisfies(item -> {
            assertThat(item.status()).isEqualTo("REQUESTED");
            assertThat(item.verdict()).isNull();
            assertThat(item.score()).isNull();
        });
        jdbc.update("UPDATE exams SET result_revision=result_revision+1 WHERE id=?", examId);
        assertThatThrownBy(() -> submissionHistory.read(examId, "7", null, page.nextCursor(), 1))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.RESULT_CURSOR_EXPIRED)
            );
    }

    /** 전체 종료 전 결과를 숨기고 확정 결과는 저장한 스냅샷에서 읽는다. */
    @Test
    @DisplayName("시험 결과는 종료 전 비공개이며 확정 후 스냅샷을 조회한다")
    void resultsFollowVisibilityAndSnapshot() {
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        submissions.submit(examId, "7", new Request("101", "java", "코드", "1"));
        assertThat(results.me(examId, "7").resultStatus()).isEqualTo("HIDDEN");
        assertThat(results.me(examId, "7").problems()).isEmpty();
        assertThatThrownBy(() -> results.standings(examId, "7", null, 20))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.EXAM_RESULT_NOT_AVAILABLE)
            );
        jdbc.update("UPDATE exams SET status='CLOSED' WHERE id=?", examId);
        var provisional = results.me(examId, "7");
        assertThat(provisional.resultStatus()).isEqualTo("PROVISIONAL");
        assertThat(provisional.problems().getFirst().pending()).isTrue();
        jdbc.update("UPDATE exam_submissions SET status='FAILED',is_zero_confirmed=true WHERE exam_id=?", examId);
        jdbc
            .update(
                "UPDATE exam_participants SET status='FINISHED',auto_submitted_at=NOW(6) WHERE id=?",
                participantId
            );
        assertThat(finalization.finalizeExam(Long.parseLong(examId))).isTrue();
        var fixed = results.me(examId, "7");
        assertThat(fixed.resultStatus()).isEqualTo("FINALIZED");
        assertThat(fixed.score()).isEqualTo("0.00");
        assertThat(fixed.rank()).isEqualTo(1);
        assertThat(fixed.problems().getFirst().pending()).isFalse();
        jdbc.update("UPDATE exam_participants SET total_score=99 WHERE id=?", participantId);
        assertThat(results.me(examId, "7").score()).isEqualTo("0.00");
        assertThat(results.standings(examId, "7", null, 20).items().getFirst().score()).isEqualTo("0.00");
    }

    /** 순위 캐시는 버전 역행을 거절하고 다른 버전에는 적중하지 않는다. */
    @Test
    @DisplayName("순위 캐시는 버전이 같을 때만 조회하고 역행을 거절한다")
    void rankingCacheRejectsOlderVersion() {
        long exam = Long.parseLong(examId);
        var newer = new ExamResultQueryService.Standing(participantId, "7", null, "20.00", 1, false);
        var older = new ExamResultQueryService.Standing(participantId, "7", null, "10.00", 1, false);
        rankingCache.replace(exam, 2, java.util.List.of(newer));
        rankingCache.replace(exam, 1, java.util.List.of(older));
        assertThat(rankingCache.read(exam, 1)).isNull();
        assertThat(rankingCache.read(exam, 2)).containsExactly(newer);
    }

    /** 다른 사용자는 등록이나 활성 스터디 가입이 있어야 하며 버전이 바뀌면 커서가 만료된다. */
    @Test
    @DisplayName("시험 순위는 조회 권한과 결과 버전 커서를 확인한다")
    void standingsRequireAuthorizationAndCurrentCursor() {
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc.update("UPDATE exams SET status='CLOSED' WHERE id=?", examId);
        jdbc
            .update(
                "UPDATE exam_participants SET is_ranked=true,last_valid_submitted_at=? WHERE id=?",
                now,
                participantId
            );
        jdbc
            .update(
                "INSERT INTO exam_participants (exam_id,member_id,status,participant_revision,registered_at,"
                    + "total_score,is_ranked,last_valid_submitted_at,version,created_at,updated_at) "
                    + "VALUES (?,8,'FINISHED',1,?,0,true,?,0,?,?)",
                examId,
                now,
                now,
                now,
                now
            );
        assertThatThrownBy(() -> results.standings(examId, "9", null, 1)).isInstanceOf(ContestException.class);
        var page = results.standings(examId, "7", null, 1);
        assertThat(page.hasMore()).isTrue();
        assertThat(results.standings(examId, "7", page.nextCursor(), 1).items()).hasSize(1);
        jdbc.update("UPDATE exams SET result_revision=result_revision+1 WHERE id=?", examId);
        assertThatThrownBy(() -> results.standings(examId, "7", page.nextCursor(), 1))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.RESULT_CURSOR_EXPIRED)
            );
    }

    /** 보류된 시험이 100건을 넘어도 뒤의 시험을 처리하고 저장된 위치에서 재개한다. */
    @Test
    @DisplayName("시험 확정 스케줄러는 보류된 앞 페이지와 재시작 위치를 넘어 순회한다")
    void finalizationSchedulerContinuesAcrossPagesAndResume() {
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc.update("UPDATE exams SET status='CLOSED',closed_at=? WHERE id=?", now, examId);
        for (int i = 0; i < 100; i++) {
            jdbc
                .update(
                    "INSERT INTO exams (study_id,creator_id,title,mode,starts_at,ends_at,status,exam_revision,"
                        + "total_score,result_revision,version,closed_at,created_at,updated_at) "
                        + "VALUES (12,7,'시험','FIXED',?,?,'CLOSED',1,100,0,0,?,?,?)",
                    now,
                    now,
                    now,
                    now,
                    now
                );
        }
        var ids = jdbc.queryForList("SELECT id FROM exams ORDER BY closed_at,id", Long.class);
        var finalizer = org.mockito.Mockito.mock(ExamFinalizationService.class);
        var scheduler
            = new ExamResultFinalizationScheduler(
                jdbc,
                finalizer,
                java.time.Clock.systemUTC(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                schedulerCursors
            );
        String job = "ExamResultFinalizationScheduler";
        schedulerCursors.save(job, new ExamSchedulerCursorStore.Cursor(now, ids.get(98)));
        scheduler.run();
        verify(finalizer, times(1)).finalizeExam(ids.get(99));
        verify(finalizer, times(1)).finalizeExam(ids.get(100));
        org.mockito.Mockito.reset(finalizer);
        scheduler.run();
        verify(finalizer, times(101)).finalizeExam(org.mockito.ArgumentMatchers.anyLong());
        schedulerCursors.clear(job);
    }

    /** 해결되지 않은 접수도 한 번 시도 후 다음 페이지로 넘긴다. */
    @Test
    @DisplayName("접수 대사 스케줄러는 실패한 앞 페이지를 넘어 순회한다")
    void reconciliationSchedulerContinuesAcrossPagesAndResume() {
        var now
            = LocalDateTime
                .now(ClockConfiguration.SERVICE_ZONE)
                .minusMinutes(2)
                .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        for (int i = 1; i <= 101; i++) {
            jdbc
                .update(
                    "INSERT INTO exam_submissions (exam_participant_id,exam_id,problem_id,draft_seq,language,"
                        + "source_code,submission_type,received_at,status,is_zero_confirmed,created_at,updated_at) "
                        + "VALUES (?,?,101,?,'JAVA','코드','MANUAL',?,'ACCEPTED',false,?,?)",
                    participantId,
                    examId,
                    i,
                    now,
                    now,
                    now
                );
        }
        var ids = jdbc.queryForList("SELECT id FROM exam_submissions ORDER BY received_at,id", Long.class);
        var client = org.mockito.Mockito.mock(com.backtoback.contest.exam.client.ExamReceiptClient.class);
        var reconcile = org.mockito.Mockito.mock(ExamReceiptReconciliation.class);
        var scheduler
            = new ContestSubmissionReconciliationScheduler(
                jdbc,
                client,
                reconcile,
                java.time.Clock.systemUTC(),
                new io.micrometer.core.instrument.simple.SimpleMeterRegistry(),
                schedulerCursors
            );
        String job = "ContestSubmissionReconciliationScheduler";
        schedulerCursors.save(job, new ExamSchedulerCursorStore.Cursor(now, ids.get(98)));
        scheduler.run();
        verify(client, times(1)).find(ids.get(99));
        verify(client, times(1)).find(ids.get(100));
        org.mockito.Mockito.reset(client);
        scheduler.run();
        verify(client, times(101)).find(org.mockito.ArgumentMatchers.anyLong());
        schedulerCursors.clear(job);
    }

    /** 원격 미확인은 보류하고 확정 실패한 관리자 작업은 차단하지 않는다. */
    @Test
    @DisplayName("PENDING 관리자 작업은 확정을 막고 확인된 FAILED 작업은 막지 않는다")
    void finalizationWaitsForUnconfirmedOperation() {
        // given
        autoSubmissions.finish(examId, "7");
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc.update("UPDATE exams SET status='CLOSED',closed_at=? WHERE id=?", now, examId);
        jdbc
            .update(
                "INSERT INTO result_snapshots(context,context_id,result_revision,payload,created_at) "
                    + "VALUES ('EXAM',?,0,'{}',?)",
                examId,
                now
            );
        jdbc
            .update(
                "INSERT INTO contest_admin_operations(operation_id,operation_type,context,context_id,request_hash,"
                    + "state,actor_member_id,reason,created_at,updated_at) "
                    + "VALUES ('pending-test','EXAM_RETRY','EXAM',?,?,'PENDING',7,'확인',?,?)",
                examId,
                "a".repeat(64),
                now,
                now
            );
        // when & then
        assertThat(finalization.finalizeExam(Long.parseLong(examId))).isFalse();
        jdbc.update("UPDATE contest_admin_operations SET state='FAILED' WHERE operation_id='pending-test'");
        assertThat(finalization.finalizeExam(Long.parseLong(examId))).isTrue();
        assertThat(finalization.finalizeExam(Long.parseLong(examId))).isFalse();
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM result_snapshots WHERE context_id=?", Integer.class, examId)
        ).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT status FROM exams WHERE id=?", String.class, examId))
            .isEqualTo("FINALIZED");
    }

    /** 실패 제출은 관리자 0점 종결이 되기 전까지 확정을 막는다. */
    @Test
    @DisplayName("실패 접수는 0점 확정 전까지 시험 결과 확정을 보류한다")
    void finalizationRequiresFailedReceiptResolution() {
        // given
        given(content.supportedLanguages(Long.parseLong(examId), 101, 1001)).willReturn(java.util.List.of("java"));
        var receipt = submissions.submit(examId, "7", new Request("101", "java", "코드", "1"));
        autoSubmissions.finish(examId, "7");
        transactions
            .executeWithoutResult(
                status -> judgements
                    .apply(
                        new ExamJudgementService.Result(
                            Long.parseLong(examId),
                            Long.parseLong(receipt.examSubmissionId()),
                            9001,
                            7,
                            101,
                            1001,
                            1,
                            "FAILED",
                            null,
                            null,
                            null,
                            receipt.receivedAt()
                        )
                    )
            );
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc.update("UPDATE exams SET status='CLOSED',closed_at=? WHERE id=?", now, examId);
        // when & then
        assertThat(finalization.finalizeExam(Long.parseLong(examId))).isFalse();
        jdbc.update("UPDATE exam_submissions SET is_zero_confirmed=true WHERE id=?", receipt.examSubmissionId());
        assertThat(finalization.finalizeExam(Long.parseLong(examId))).isTrue();
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM result_snapshots WHERE context_id=?", Integer.class, examId)
        ).isEqualTo(1);
    }

    /** 확정 이벤트 저장 실패는 상태와 스냅샷을 함께 롤백한다. */
    @Test
    @DisplayName("확정 Outbox 실패는 시험 상태와 새 스냅샷을 롤백한다")
    void finalizationRollsBackWhenOutboxFails() {
        // given
        autoSubmissions.finish(examId, "7");
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc.update("UPDATE exams SET status='CLOSED',closed_at=? WHERE id=?", now, examId);
        doThrow(new IllegalStateException("outbox failure"))
            .when(outbox)
            .append(anyString(), anyString(), anyString(), anyString(), anyString(), any());
        // when & then
        assertThatThrownBy(() -> finalization.finalizeExam(Long.parseLong(examId)))
            .isInstanceOf(IllegalStateException.class);
        assertThat(jdbc.queryForObject("SELECT status FROM exams WHERE id=?", String.class, examId))
            .isEqualTo("CLOSED");
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM result_snapshots WHERE context_id=?", Integer.class, examId)
        ).isZero();
    }

    /** 같은 점수와 마지막 접수 시간이면 공동 순위로 확정하고 다음 순위를 건너뛴다. */
    @Test
    @DisplayName("완전 동점은 공동 순위로 확정하고 스냅샷에 코드 원문을 넣지 않는다")
    void finalizationAssignsSharedRanks() {
        // given
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        jdbc
            .update(
                "UPDATE exam_participants SET status='FINISHED',auto_submitted_at=?,finished_at=? WHERE id=?",
                now,
                now,
                participantId
            );
        for (long member : new long[] {
            8,
            9
        }) {
            jdbc
                .update(
                    "INSERT INTO exam_participants (exam_id,member_id,status,participant_revision,registered_at,"
                        + "entered_at,personal_ends_at,finished_at,total_score,auto_submitted_at,is_ranked,version,"
                        + "created_at,updated_at) VALUES (?,?,'FINISHED',3,?,?,?,?,0,?,false,0,?,?)",
                    examId,
                    member,
                    now.minusHours(1),
                    now.minusMinutes(30),
                    now,
                    now,
                    now,
                    now,
                    now
                );
        }
        var ids
            = jdbc
                .queryForList(
                    "SELECT id FROM exam_participants WHERE exam_id=? ORDER BY member_id",
                    Long.class,
                    examId
                );
        for (int i = 0; i < ids.size(); i++) {
            var received = i == 2 ? now.minusSeconds(1) : now.minusSeconds(2);
            jdbc
                .update(
                    "INSERT INTO exam_submissions (exam_participant_id,exam_id,problem_id,draft_seq,language,"
                        + "source_code,submission_type,received_at,status,submission_id,judge_attempt,verdict,"
                        + "passed_count," + "total_count,score,is_zero_confirmed,created_at,updated_at) "
                        + "VALUES (?,?,101,1,'JAVA','비공개코드','MANUAL',?,'JUDGED',?,1,'AC',1,1,50,false,?,?)",
                    ids.get(i),
                    examId,
                    received,
                    9000 + i,
                    now,
                    now
                );
        }
        jdbc.update("UPDATE exams SET status='CLOSED',closed_at=? WHERE id=?", now, examId);
        // when
        assertThat(finalization.finalizeExam(Long.parseLong(examId))).isTrue();
        // then
        assertThat(
            jdbc
                .queryForList(
                    "SELECT final_rank FROM exam_participants WHERE exam_id=? ORDER BY member_id",
                    Integer.class,
                    examId
                )
        ).containsExactly(1, 1, 3);
        String snapshot
            = jdbc.queryForObject("SELECT payload FROM result_snapshots WHERE context_id=?", String.class, examId);
        assertThat(snapshot).contains("firstAcceptedAt", "submissionCount").doesNotContain("비공개코드");
    }

    /** 동일 시험의 동시 확정은 부모 잠금으로 직렬화하고 스냅샷을 한 번만 생성한다. */
    @Test
    @DisplayName("동시 확정은 하나의 스냅샷과 확정 이벤트만 저장한다")
    void concurrentFinalizationCreatesOneSnapshot() throws Exception {
        // given
        autoSubmissions.finish(examId, "7");
        var now = LocalDateTime.now(ClockConfiguration.SERVICE_ZONE);
        jdbc.update("UPDATE exams SET status='CLOSED',closed_at=? WHERE id=?", now, examId);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                return finalization.finalizeExam(Long.parseLong(examId));
            });
            var second = pool.submit(() -> {
                start.await();
                return finalization.finalizeExam(Long.parseLong(examId));
            });
            // when
            start.countDown();
            var results = java.util.List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
            // then
            assertThat(results).containsExactlyInAnyOrder(true, false);
        }
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM result_snapshots WHERE context_id=?", Integer.class, examId)
        ).isEqualTo(1);
        assertThat(
            jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE event_type='ExamFinalized'", Integer.class)
        ).isEqualTo(1);
    }

    /** 동일 창의 뒤 요청만 저장하고 밀린 다른 문제는 0과 null을 반환한다. */
    @Test
    @DisplayName("같은 참가자의 버퍼에서 밀린 요청은 미저장 문제의 순번 0과 null 시각을 반환한다")
    void bufferKeepsLastRequest() throws Exception {
        // given
        String prefix = "exam:draft:buffer:" + participantId;
        var openSlot = new DefaultRedisScript<Long>("""
            local time = redis.call('TIME')
            local due = tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000) + 60000
            redis.call('SET', KEYS[1], string.format('%.0f', due), 'PX', 120000)
            return due
            """, Long.class);
        long due = redis.execute(openSlot, List.of(prefix + ":due"));
        String slot = prefix + ":slot:" + due;
        RedisScript<List> enqueue = bufferScript("ENQUEUE");
        RedisScript<List> close = bufferScript("CLOSE_SLOT");
        var firstEnqueued = new CountDownLatch(1);
        var bothEnqueued = new CountDownLatch(2);
        var closeWindow = new CountDownLatch(1);
        doAnswer(invocation -> {
            List<?> registered = (List<?>)invocation.callRealMethod();
            assertThat(((Number)registered.getFirst()).longValue()).isEqualTo(due);
            firstEnqueued.countDown();
            bothEnqueued.countDown();
            if (!closeWindow.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("버퍼 마감 제어 대기 초과");
            }
            return List.of(due, 0L);
        }).when(redis).execute(eq(enqueue), eq(List.of(prefix)), anyString());
        // 등록과 저장은 실제 Redis를 사용하고, 두 요청 등록 뒤 마감 시각만 앞당긴다.
        doAnswer(invocation -> redis.execute(close, List.of(slot), "0"))
            .when(redis)
            .execute(eq(close), eq(List.of(slot)), eq(Long.toString(due)));

        try (var pool = Executors.newFixedThreadPool(2)) {
            // when
            var first = pool.submit(() -> buffer.save(examId, "7", "102", new SaveRequest("java", "첫 코드", "1")));
            try {
                assertThat(firstEnqueued.await(5, TimeUnit.SECONDS)).isTrue();
                var last = pool.submit(() -> buffer.save(examId, "7", "101", new SaveRequest("java", "마지막", "1")));
                assertThat(bothEnqueued.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_drafts", Integer.class)).isZero();
                assertThat(first.isDone()).isFalse();
                assertThat(last.isDone()).isFalse();
                closeWindow.countDown();
                var saved = last.get(5, TimeUnit.SECONDS);
                var skipped = first.get(5, TimeUnit.SECONDS);
                // then
                assertThat(saved.applied()).isTrue();
                assertThat(saved.serverSeq()).isEqualTo("1");
                assertThat(saved.savedAt()).isNotNull();
                assertThat(skipped.applied()).isFalse();
                assertThat(skipped.serverSeq()).isEqualTo("0");
                assertThat(skipped.savedAt()).isNull();
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_drafts", Integer.class)).isEqualTo(1);
                assertThat(jdbc.queryForObject("SELECT problem_id FROM exam_drafts", Long.class)).isEqualTo(101L);
                assertThat(jdbc.queryForObject("SELECT source_code FROM exam_drafts", String.class)).isEqualTo("마지막");
                assertThat(redis.opsForHash().get(slot, "status")).isEqualTo("done");
                assertThat(redis.getExpire(slot)).isPositive();
            } finally {
                closeWindow.countDown();
            }
        }
    }

    @SuppressWarnings("unchecked")
    private <T> RedisScript<T> bufferScript(String field) {
        return (RedisScript<T>)ReflectionTestUtils.getField(ExamDraftBuffer.class, field);
    }

    @Test
    @DisplayName("버퍼에서 밀린 요청도 개인 마감이 지나면 저장 가능 응답을 반환하지 않는다")
    void skippedRequestRechecksDeadline() {
        var work = store.prepare(examId, "7", "101", new SaveRequest("java", "코드", "1"));
        jdbc
            .update(
                "UPDATE exam_participants SET personal_ends_at=DATE_SUB(NOW(6), INTERVAL 1 SECOND) WHERE id=?",
                participantId
            );

        assertThatThrownBy(() -> store.skipped(work))
            .isInstanceOf(ContestException.class)
            .extracting("errorCode")
            .isEqualTo(ContestErrorCode.EXAM_CLOSED);
    }

    /** 참가자 잠금 대기 중 마감이 지나면 새 저장을 거절한다. */
    @Test
    @DisplayName("부모 잠금 대기 중 개인 마감이 바뀌면 저장하지 않는다")
    void rechecksDeadlineAfterLock() throws Exception {
        // given
        var work = store.prepare(examId, "7", "101", new SaveRequest("java", "코드", "1"));
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var update = pool.submit(() -> transactions.executeWithoutResult(status -> {
                jdbc.update("UPDATE exams SET version=version+1 WHERE id=?", examId);
                jdbc
                    .update(
                        "UPDATE exam_participants SET personal_ends_at=? WHERE id=?",
                        LocalDateTime.now(ClockConfiguration.SERVICE_ZONE).minusSeconds(1),
                        participantId
                    );
                locked.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Timed out");
                    }
                } catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(failure);
                }
            }));
            assertThat(locked.await(5, TimeUnit.SECONDS)).isTrue();
            // when & then
            var save = pool.submit(() -> store.save(work));
            try {
                assertThatThrownBy(() -> save.get(200, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            } finally {
                release.countDown();
            }
            update.get(5, TimeUnit.SECONDS);
            assertThatThrownBy(() -> save.get(5, TimeUnit.SECONDS))
                .hasCauseInstanceOf(ContestException.class)
                .satisfies(
                    error -> assertThat(((ContestException)error.getCause()).getErrorCode())
                        .isEqualTo(ContestErrorCode.EXAM_CLOSED)
                );
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_drafts", Integer.class)).isZero();
    }

    /** 동시 언어 저장은 참가자 잠금으로 직렬화하고 최대 순번을 유지한다. */
    @Test
    @DisplayName("서로 다른 언어의 동시 저장은 큰 순번을 최신 Draft로 유지한다")
    void concurrentLanguagesKeepMaximum() throws Exception {
        // given
        var smaller = store.prepare(examId, "7", "101", new SaveRequest("java", "이전", "1"));
        var larger = store.prepare(examId, "7", "101", new SaveRequest("python3", "최신", "2"));
        var start = new CountDownLatch(1);
        // when
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> {
                start.await();
                return store.save(smaller);
            });
            var second = pool.submit(() -> {
                start.await();
                return store.save(larger);
            });
            start.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertThat(second.get(5, TimeUnit.SECONDS).applied()).isTrue();
        }
        // then
        assertThat(
            jdbc
                .queryForObject(
                    "SELECT MAX(seq) FROM exam_drafts WHERE exam_participant_id=? AND problem_id=101",
                    Long.class,
                    participantId
                )
        ).isEqualTo(2L);
    }

    /** 정지된 회원의 신규 저장은 참가와 기존 저장본을 바꾸지 않는다. */
    @Test
    @DisplayName("정지 회원은 새 Draft를 저장할 수 없다")
    void suspendedCannotSave() {
        // given
        var work = store.prepare(examId, "7", "101", new SaveRequest("java", "코드", "1"));
        jdbc.update("UPDATE member_replicas SET member_status='SUSPENDED' WHERE member_id=7");
        // when & then
        assertThatThrownBy(() -> store.save(work))
            .isInstanceOf(ContestException.class)
            .satisfies(
                error -> assertThat(((ContestException)error).getErrorCode())
                    .isEqualTo(ContestErrorCode.MEMBER_SUSPENDED)
            );
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM exam_drafts", Integer.class)).isZero();
    }

    /** Redis의 시간 경계 전에는 마지막 요청을 확정하지 않는다. */
    @Test
    @DisplayName("버퍼 마감 전에 깨어나도 마감 직전의 마지막 요청을 선택한다")
    void waitsForRedisDeadline() throws Exception {
        // given
        var timeScript = new DefaultRedisScript<Long>("""
            local time = redis.call('TIME')
            return tonumber(time[1]) * 1000 + math.floor(tonumber(time[2]) / 1000) + 1000
            """, Long.class);
        long due = redis.execute(timeScript, java.util.List.of());
        String key = "exam:draft:buffer:" + participantId + ":slot:" + due;
        redis.opsForHash().put(key, "last", "first");
        try (var pool = Executors.newSingleThreadExecutor()) {
            // when
            var result
                = pool.submit(() -> (String)ReflectionTestUtils.invokeMethod(buffer, "lastAfterDeadline", key, due));
            assertThatThrownBy(() -> result.get(100, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            redis.opsForHash().put(key, "last", "last");
            // then
            assertThat(result.get(3, TimeUnit.SECONDS)).isEqualTo("last");
        }
    }
}
