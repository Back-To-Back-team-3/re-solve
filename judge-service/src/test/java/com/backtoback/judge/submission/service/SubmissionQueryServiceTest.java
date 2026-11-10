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
