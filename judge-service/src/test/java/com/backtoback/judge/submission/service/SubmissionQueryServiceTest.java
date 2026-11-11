package com.backtoback.judge.submission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

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
import com.backtoback.judge.submission.domain.Submission.Context;
import com.backtoback.judge.submission.dto.SubmissionListRequest;
import com.backtoback.judge.submission.dto.SubmissionListResponse;

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
        SubmissionQueryService.class
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

    /** 본인 제출만 안정적으로 페이지에 담고 시험·진단의 판정과 실행 지표를 가린다. */
    @Test
    @DisplayName("내 제출 목록은 본인 시험·진단을 포함하고 상세 연결 ID만 공개한다")
    void listsOwnedPage() {
        insertExternal(4, 1, Context.EXAM, 41);
        insertExternal(5, 1, Context.DIAGNOSIS, 51);
        insertExternal(6, 1, Context.CONTEST, 61);
        insertExternal(7, 2, Context.DIAGNOSIS, 71);
        jdbc.update("UPDATE submissions SET max_time_ms=12,max_memory_kb=8192 WHERE id=1");

        var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
        statistics.clear();
        var first = queries.list(1, new SubmissionListRequest("1", null, null, 0, 2, null));
        assertThat(first.totalElements()).isEqualTo(4);
        assertThat(first.hasNext()).isTrue();
        assertThat(first.content()).extracting(SubmissionListResponse::submissionId).containsExactly("5", "4");
        assertThat(first.content()).extracting(SubmissionListResponse::contextId).containsExactly("51", "41");
        assertThat(first.content()).extracting(SubmissionListResponse::context).containsExactly("DIAGNOSIS", "EXAM");
        assertThat(first.content())
            .extracting(SubmissionListResponse::submittedAt)
            .containsExactly("2026-10-31T09:00:00+09:00", "2026-10-31T09:00:00.000000+09:00");
        assertThat(first.content()).allSatisfy(item -> {
            assertThat(item.status()).isEqualTo("COMPLETED");
            assertThat(item.verdict()).isNull();
            assertThat(item.maxTimeMs()).isNull();
            assertThat(item.maxMemoryKb()).isNull();
        });

        var second = queries.list(1, new SubmissionListRequest("1", null, null, 1, 2, null));
        assertThat(second.hasNext()).isFalse();
        assertThat(second.content()).extracting(SubmissionListResponse::submissionId).containsExactly("2", "1");
        var practice = second.content().getLast();
        assertThat(practice.submittedAt()).isEqualTo("2026-10-31T09:00:00+09:00");
        assertThat(practice.contextId()).isNull();
        assertThat(practice.verdict()).isEqualTo(JudgeVerdict.AC);
        assertThat(practice.maxTimeMs()).isEqualTo(12);
        assertThat(practice.maxMemoryKb()).isEqualTo(8192);
        assertThat(statistics.getEntityLoadCount()).isZero();
    }

    /** 문맥과 판정 필터를 조합해도 가린 판정이 목록이나 페이지 건수에 반영되지 않는다. */
    @Test
    @DisplayName("시험·진단 AC는 판정 필터의 조회 결과와 페이지 건수에서 제외된다")
    void excludesMaskedVerdictsFromFiltering() {
        insertExternal(4, 1, Context.EXAM, 41);
        insertExternal(5, 1, Context.DIAGNOSIS, 51);

        for (var context : List.of(Context.EXAM, Context.DIAGNOSIS)) {
            var unfiltered = queries.list(1, new SubmissionListRequest(null, context, null, 0, 20, null));
            assertThat(unfiltered.content()).hasSize(1);

            var filtered = queries.list(1, new SubmissionListRequest(null, context, JudgeVerdict.AC, 0, 20, null));
            assertThat(filtered.content()).isEmpty();
            assertThat(filtered.totalElements()).isZero();
        }

        var filtered = queries.list(1, new SubmissionListRequest(null, null, JudgeVerdict.AC, 0, 1, null));
        assertThat(filtered.content()).extracting(SubmissionListResponse::submissionId).containsExactly("1");
        assertThat(filtered.totalElements()).isEqualTo(1);
        assertThat(filtered.hasNext()).isFalse();
    }

    /**
     * 판정·지표를 가진 외부 제출을 저장한다.
     *
     * @param id Judge 제출 ID
     * @param memberId 제출 소유자 ID
     * @param context 제출 문맥
     * @param contextId 시험·대회·진단 식별자
     */
    private void insertExternal(long id, long memberId, Context context, long contextId) {
        jdbc.update("""
            INSERT INTO submissions(id,member_id,problem_id,problem_revision_id,context,context_id,
                source_submission_id,language,source_code,code_size_bytes,status,verdict,judge_attempt,max_time_ms,
                max_memory_kb,submitted_at,created_at,updated_at)
            VALUES (?,?,1,1,?,?,?,'java','x',1,'COMPLETED','AC',1,12,8192,
                '2026-10-31 09:00:00','2026-10-31 09:00:00','2026-10-31 09:00:00')
            """, id, memberId, context.name(), contextId, id + 100);
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
