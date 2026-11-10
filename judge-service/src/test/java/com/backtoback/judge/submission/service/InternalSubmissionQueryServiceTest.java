package com.backtoback.judge.submission.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.judge.global.config.PersistenceConfiguration;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.submission.domain.Submission.Context;
import com.backtoback.judge.submission.dto.AcceptedSubmissionHistoryResponse;

/** 실제 MySQL에서 회원별 일반 풀이 AC 이력의 조회 범위와 페이지 경계를 검증한다. */
@DataJpaTest(properties = "spring.jpa.show-sql=false")
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    {
        PersistenceConfiguration.class,
        InternalSubmissionQueryService.class
    }
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class InternalSubmissionQueryServiceTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private InternalSubmissionQueryService queries;

    /**
     * 격리된 MySQL 데이터소스를 연결한다.
     *
     * @param registry 테스트 컨텍스트의 동적 속성
     */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /** 다음 테스트에 이전 페이지의 커밋된 데이터가 남지 않도록 정리한다. */
    @BeforeEach
    void clearSubmissions() {
        jdbc.update("DELETE FROM submissions");
    }

    /** 문맥·회원·판정 필터를 적용하고 새로 들어온 큰 ID의 정답을 이어서 조회하는지 확인한다. */
    @Test
    @DisplayName("회원별 일반 AC만 조회하고 새 제출을 다음 페이지에서 중복 없이 반환한다")
    void paginatesAcceptedPracticeHistory() {
        insertSubmission(1, 1, Context.PRACTICE, JudgeVerdict.AC);
        insertSubmission(2, 1, Context.PRACTICE, JudgeVerdict.WA);
        insertSubmission(3, 2, Context.PRACTICE, JudgeVerdict.WA);
        insertSubmission(4, 1, Context.EXAM, JudgeVerdict.AC);
        insertSubmission(5, 1, Context.DIAGNOSIS, JudgeVerdict.AC);
        insertSubmission(6, 1, Context.CONTEST, JudgeVerdict.AC);
        insertSubmission(7, 2, Context.PRACTICE, JudgeVerdict.AC);
        insertSubmission(8, 1, Context.PRACTICE, JudgeVerdict.AC);

        var first = queries.acceptedHistory(1, 0, 1);
        assertThat(first.items()).extracting(AcceptedSubmissionHistoryResponse.Item::submissionId).containsExactly("1");
        assertThat(first.nextCursor()).isEqualTo("1");

        insertSubmission(9, 1, Context.PRACTICE, JudgeVerdict.AC);
        long after = Long.parseLong(first.nextCursor());
        var last = queries.acceptedHistory(1, after, 2);
        assertThat(last.items())
            .extracting(AcceptedSubmissionHistoryResponse.Item::submissionId)
            .containsExactly("8", "9");
        assertThat(last.nextCursor()).isNull();
        assertThat(queries.acceptedHistory(1, after, 2)).isEqualTo(last);

        var empty = queries.acceptedHistory(1, 9, 2);
        assertThat(empty.items()).isEmpty();
        assertThat(empty.nextCursor()).isNull();
        assertThat(queries.acceptedHistory(99, 0, 2)).isEqualTo(empty);
    }

    /** 커서가 이미 지나간 제출이 AC로 바뀌면 첫 페이지부터 다시 조회해야 찾을 수 있는지 확인한다. */
    @Test
    @DisplayName("커서보다 작은 제출이 나중에 AC가 되면 새 순회에서 조회된다")
    void findsLateAcceptedSubmissionOnNewScan() {
        insertSubmission(1, 1, Context.PRACTICE, null);
        insertSubmission(2, 1, Context.PRACTICE, JudgeVerdict.AC);
        insertSubmission(3, 1, Context.PRACTICE, JudgeVerdict.AC);

        var first = queries.acceptedHistory(1, 0, 1);
        assertThat(first.items()).extracting(AcceptedSubmissionHistoryResponse.Item::submissionId).containsExactly("2");
        assertThat(first.nextCursor()).isEqualTo("2");
        jdbc.update("UPDATE submissions SET status='COMPLETED',verdict='AC' WHERE id=1");

        var next = queries.acceptedHistory(1, Long.parseLong(first.nextCursor()), 2);
        assertThat(next.items()).extracting(AcceptedSubmissionHistoryResponse.Item::submissionId).containsExactly("3");
        assertThat(next.nextCursor()).isNull();
        assertThat(queries.acceptedHistory(1, 0, 3).items())
            .extracting(AcceptedSubmissionHistoryResponse.Item::submissionId)
            .containsExactly("1", "2", "3");
    }

    /**
     * 조회 범위를 검증할 제출을 저장한다.
     *
     * @param id 제출 ID
     * @param memberId 제출 소유 회원 ID
     * @param context 제출 문맥
     * @param verdict 완료 판정, 대기 중인 제출은 {@code null}
     */
    private void insertSubmission(long id, long memberId, Context context, JudgeVerdict verdict) {
        boolean practice = context == Context.PRACTICE;
        jdbc
            .update(
                """
                    INSERT INTO submissions(id,member_id,problem_id,problem_revision_id,context,context_id,
                    source_submission_id,idempotency_key,request_hash,language,source_code,code_size_bytes,
                    status,verdict,judge_attempt,submitted_at,created_at,updated_at)
                    VALUES (?,?,1,1,?,?,?,?,?,'java','x',1,?,?,1,
                    '2026-10-31 09:00:00','2026-10-31 09:00:00','2026-10-31 09:00:00')
                    """,
                id,
                memberId,
                context.name(),
                practice ? null : 1L,
                practice ? null : id,
                practice ? "key" + id : null,
                practice ? "a".repeat(64) : null,
                verdict == null ? "QUEUED" : "COMPLETED",
                verdict == null ? null : verdict.name()
            );
    }
}
