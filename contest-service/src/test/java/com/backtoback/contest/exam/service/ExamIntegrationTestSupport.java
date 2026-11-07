package com.backtoback.contest.exam.service;

import static org.mockito.BDDMockito.given;

import java.time.LocalDateTime;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.event.service.EventConsumer;
import com.backtoback.common.event.service.EventJsonCodec;
import com.backtoback.common.event.service.OutboxEventWriter;
import com.backtoback.common.time.ClockConfiguration;
import com.backtoback.contest.exam.client.ExamContentClient;
import com.backtoback.contest.exam.store.ExamRankingCache;
import com.backtoback.contest.exam.store.ExamSchedulerCursorStore;
import com.backtoback.contest.exam.store.ExamSubmissionRateLimiter;

/** 시험 통합 테스트가 격리된 저장소와 초기 데이터를 공유한다. */
@SpringBootTest(
    properties = {
        "spring.cloud.aws.sns.enabled=false",
        "spring.cloud.aws.sqs.enabled=false"
    }
)
@Testcontainers(disabledWithoutDocker = true)
public abstract class ExamIntegrationTestSupport {
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("contest_db");
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    /** 테스트 컨텍스트의 격리된 저장소 주소를 등록한다. */
    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        // 컨텍스트가 재사용되는 동안 컨테이너 주소가 바뀌지 않도록 전체 테스트에서 공유한다.
        MYSQL.start();
        REDIS.start();
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @MockitoBean
    protected ExamLifecycleScheduler scheduler;
    @MockitoBean
    protected ExamAutoSubmissionScheduler autoScheduler;
    @MockitoBean
    protected ExamResultFinalizationScheduler finalizationScheduler;
    @MockitoBean
    protected ExamRankingCacheRebuildScheduler rankingCacheScheduler;
    @MockitoBean
    protected ContestSubmissionReconciliationScheduler reconciliationScheduler;
    @MockitoBean
    protected MemberReplicaService refresh;
    @MockitoBean
    protected ExamContentClient content;
    @MockitoBean
    protected ExamSubmissionRateLimiter limiter;
    @MockitoSpyBean
    protected OutboxEventWriter outbox;
    @Autowired
    protected ExamDraftStore store;
    @Autowired
    protected ExamDraftBuffer buffer;
    @Autowired
    protected ExamSubmissionService submissions;
    @Autowired
    protected ExamAutoSubmissionService autoSubmissions;
    @Autowired
    protected EventConsumer consumer;
    @Autowired
    protected EventJsonCodec codec;
    @Autowired
    protected ExamReceiptReconciliation reconciliation;
    @Autowired
    protected ExamJudgementService judgements;
    @Autowired
    protected ExamEventSnapshotService eventSnapshots;
    @Autowired
    protected ExamSubmissionHistoryService submissionHistory;
    @Autowired
    protected ExamFinalizationService finalization;
    @Autowired
    protected ExamResultQueryService results;
    @Autowired
    protected ExamRankingCache rankingCache;
    @Autowired
    protected ExamSchedulerCursorStore schedulerCursors;
    @Autowired
    protected JdbcTemplate jdbc;
    @Autowired
    protected StringRedisTemplate redis;
    @Autowired
    protected TransactionTemplate transactions;
    protected String examId;
    protected long participantId;

    /** 입장한 참가자와 두 문제를 저장한다. */
    @BeforeEach
    protected void setUp() {
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
        org.mockito.Mockito
            .lenient()
            .when(
                content
                    .supportedLanguages(
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong(),
                        org.mockito.ArgumentMatchers.anyLong()
                    )
            )
            .thenReturn(java.util.List.of("java", "python3", "cpp"));
    }

}
