package com.backtoback.judge.submission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.hibernate.SessionFactory;
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
import com.backtoback.judge.global.error.JudgeErrorCode;
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.domain.JudgeVerdict;
import com.backtoback.judge.submission.domain.Submission;
import com.backtoback.judge.submission.dto.AcceptedSubmissionHistoryResponse;

import jakarta.persistence.EntityManagerFactory;

/** 실제 MySQL에서 본인 제출 필터·페이지와 코드 접근 경계를 검증한다. */
@DataJpaTest(
    properties = {
        "spring.jpa.show-sql=false",
        "spring.jpa.properties.hibernate.generate_statistics=true"
    }
)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    {
        PersistenceConfiguration.class,
        SubmissionQueryService.class,
        InternalSubmissionQueryService.class
    }
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class SubmissionQueryServiceTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SubmissionQueryService queries;
    @Autowired
    private InternalSubmissionQueryService internalQueries;
    @Autowired
    private EntityManagerFactory entityManagerFactory;

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @BeforeEach
    void prepare() {
        jdbc.update("DELETE FROM submissions");
        for (int id = 1; id <= 3; id++) {
            jdbc.update("""
                INSERT INTO submissions(id,member_id,problem_id,problem_revision_id,context,idempotency_key,
                request_hash,language,source_code,code_size_bytes,status,verdict,judge_attempt,submitted_at,
                created_at,updated_at) VALUES (?,?,1,1,'PRACTICE',?,REPEAT('a',64),'java','x',1,'COMPLETED',?,1,
                '2026-10-31 09:00:00','2026-10-31 09:00:00','2026-10-31 09:00:00')
                """, id, id == 3 ? 2 : 1, "key" + id, id == 1 ? "AC" : "WA");
        }
    }

    /** 커서로 이어서 조회할 때 다른 문맥·회원·판정은 제외하고 새로 들어온 정답을 한 번만 반환하는지 검증한다. */
    @Test
    @DisplayName("회원별 일반 AC만 조회하고 새 제출을 다음 페이지에서 중복 없이 반환한다")
    void paginatesAcceptedPracticeHistory() {
        insertAccepted(4, 1, Submission.Context.EXAM);
        insertAccepted(5, 1, Submission.Context.DIAGNOSIS);
        insertAccepted(6, 1, Submission.Context.CONTEST);
        insertAccepted(7, 2, Submission.Context.PRACTICE);
        insertAccepted(8, 1, Submission.Context.PRACTICE);

        var first = internalQueries.acceptedHistory(1, 0, 1);
        assertThat(first.items()).extracting(AcceptedSubmissionHistoryResponse.Item::submissionId).containsExactly("1");
        assertThat(first.nextCursor()).isEqualTo("1");

        insertAccepted(9, 1, Submission.Context.PRACTICE);
        long after = Long.parseLong(first.nextCursor());
        var last = internalQueries.acceptedHistory(1, after, 2);
        assertThat(last.items())
            .extracting(AcceptedSubmissionHistoryResponse.Item::submissionId)
            .containsExactly("8", "9");
        assertThat(last.nextCursor()).isNull();
        assertThat(internalQueries.acceptedHistory(1, after, 2)).isEqualTo(last);

        var empty = internalQueries.acceptedHistory(1, 9, 2);
        assertThat(empty.items()).isEmpty();
        assertThat(empty.nextCursor()).isNull();
        assertThat(internalQueries.acceptedHistory(99, 0, 2)).isEqualTo(empty);
    }

    /**
     * 조회 범위를 구분할 완료된 정답 제출을 저장한다.
     *
     * @param id 제출 ID
     * @param memberId 제출 소유 회원 ID
     * @param context 제출 문맥
     */
    private void insertAccepted(long id, long memberId, Submission.Context context) {
        boolean practice = context == Submission.Context.PRACTICE;
        jdbc
            .update(
                """
                    INSERT INTO submissions(id,member_id,problem_id,problem_revision_id,context,context_id,
                    source_submission_id,idempotency_key,request_hash,language,source_code,code_size_bytes,
                    status,verdict,judge_attempt,submitted_at,created_at,updated_at)
                    VALUES (?,?,1,1,?,?,?,?,?,'java','x',1,'COMPLETED','AC',1,
                    '2026-10-31 09:00:00','2026-10-31 09:00:00','2026-10-31 09:00:00')
                    """,
                id,
                memberId,
                context.name(),
                practice ? null : 1L,
                practice ? null : id,
                practice ? "key" + id : null,
                practice ? "a".repeat(64) : null
            );
    }

    /** 본인 시험 제출을 제외하고 엔티티를 적재하지 않는 요약 페이지와 필터를 검증한다. */
    @Test
    @DisplayName("일반 제출 요약만 조회하고 시험 제출 제외·판정 필터·안정적인 정렬을 적용한다")
    void listsOwnedPage() {
        jdbc.update("""
            INSERT INTO submissions(id,member_id,problem_id,problem_revision_id,context,context_id,
            source_submission_id,language,source_code,code_size_bytes,status,verdict,judge_attempt,submitted_at,
            created_at,updated_at)
            VALUES (4,1,1,1,'EXAM',1,1,'java','exam',4,'COMPLETED','AC',1,
            '2026-10-31 09:00:00','2026-10-31 09:00:00','2026-10-31 09:00:00')
            """);
        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var page
            = queries
                .list(1, new com.backtoback.judge.submission.dto.SubmissionListRequest("1", null, null, 0, 1, null));
        assertThat(page.totalElements()).isEqualTo(2);
        assertThat(page.content().getFirst().submissionId()).isEqualTo("2");
        assertThat(page.hasNext()).isTrue();
        var filtered
            = queries
                .list(
                    1,
                    new com.backtoback.judge.submission.dto.SubmissionListRequest(
                        "1",
                        null,
                        JudgeVerdict.AC,
                        0,
                        20,
                        null
                    )
                );
        assertThat(filtered.content()).hasSize(1);
        assertThat(filtered.content().getFirst().submissionId()).isEqualTo("1");
        assertThat(statistics.getEntityLoadCount()).isZero();
        assertThat(page.content()).extracting(value -> value.submissionId()).doesNotContain("4");
        assertThat(filtered.content().getFirst().language()).isEqualTo("java");
        assertThat(filtered.content().getFirst().status()).isEqualTo("COMPLETED");
    }

    /** 타인·미존재·시험 제출을 동일한 오류 코드로 숨기는지 확인한다. */
    @Test
    @DisplayName("타인·미존재·시험 제출의 코드를 같은 오류로 거절한다")
    void protectsSource() {
        assertThatThrownBy(() -> queries.source(1, 3))
            .isInstanceOfSatisfying(
                JudgeException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(JudgeErrorCode.SUBMISSION_NOT_FOUND)
            );
        assertThatThrownBy(() -> queries.source(1, 99))
            .isInstanceOfSatisfying(
                JudgeException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(JudgeErrorCode.SUBMISSION_NOT_FOUND)
            );
        jdbc.update("""
            UPDATE submissions SET context='EXAM',context_id=1,source_submission_id=1,
            idempotency_key=NULL,request_hash=NULL WHERE id=1
            """);
        assertThatThrownBy(() -> queries.source(1, 1))
            .isInstanceOfSatisfying(
                JudgeException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(JudgeErrorCode.SUBMISSION_NOT_FOUND)
            );
        assertThat(queries.source(1, 2).sourceCode()).isEqualTo("x");
    }

}
