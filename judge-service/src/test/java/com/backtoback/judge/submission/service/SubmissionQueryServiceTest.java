package com.backtoback.judge.submission.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.backtoback.judge.global.error.JudgeException;
import com.backtoback.judge.judging.domain.JudgeVerdict;

/** 실제 MySQL에서 본인 제출 필터·페이지와 코드 접근 경계를 검증한다. */
@DataJpaTest(properties = "spring.jpa.show-sql=false")
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

    @Test
    @DisplayName("본인 목록에 판정 필터·페이지와 동일 시각의 안정적인 정렬을 적용한다")
    void listsOwnedPage() {
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
    }

    @Test
    @DisplayName("타인·미존재·시험 제출의 코드를 같은 오류로 거절한다")
    void protectsSource() {
        assertThatThrownBy(() -> queries.source(1, 3)).isInstanceOf(JudgeException.class);
        assertThatThrownBy(() -> queries.source(1, 99)).isInstanceOf(JudgeException.class);
        jdbc.update("""
            UPDATE submissions SET context='EXAM',context_id=1,source_submission_id=1,
            idempotency_key=NULL,request_hash=NULL WHERE id=1
            """);
        assertThatThrownBy(() -> queries.source(1, 1)).isInstanceOf(JudgeException.class);
        assertThat(queries.source(1, 2).sourceCode()).isEqualTo("x");
    }

}
