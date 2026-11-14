package com.backtoback.problem.problem.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.common.exception.CommonErrorCode;
import com.backtoback.problem.global.config.PersistenceConfiguration;
import com.backtoback.problem.global.error.ProblemErrorCode;
import com.backtoback.problem.global.exception.ProblemException;
import com.backtoback.problem.problem.config.ProblemImageStorageProperties;
import com.backtoback.problem.problem.repository.ProblemSearchRepository;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** 실제 DB 사용 회차·태그 선택과 저장소 읽기의 트랜잭션 분리를 확인한다. */
@DataJpaTest(
    properties = {
        "spring.jpa.show-sql=false",
        "spring.flyway.locations=classpath:db/migration,classpath:db/testmigration"
    }
)
@ImportAutoConfiguration(FlywayAutoConfiguration.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(
    {
        ProblemHintContextStore.class,
        ProblemHintContextService.class,
        ProblemReadStore.class,
        ProblemSearchRepository.class,
        PersistenceConfiguration.class,
        ProblemHintContextStoreTest.Beans.class
    }
)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Testcontainers(disabledWithoutDocker = true)
class ProblemHintContextStoreTest {
    private static final AtomicLong IDS = new AtomicLong(10000);
    private static final byte[] BODY = """
        {"description":"설명","constraints":"제한","examples":"예시"}
        """.getBytes(StandardCharsets.UTF_8);
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4");
    @Autowired
    private ProblemHintContextStore store;
    @Autowired
    private ProblemHintContextService service;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PlatformTransactionManager transactions;
    @MockitoBean
    private ProblemAssetObjectReader reader;

    /**
     * 독립 DB 연결 속성을 제공한다.
     *
     * @param registry 실행 중 컨테이너의 연결 속성
     */
    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    /** 최신 미통과 회차를 제외하고 본문 조회 시 호출자의 트랜잭션도 중지한다. */
    @Test
    @DisplayName("현재 통과 회차의 제목·태그를 선택하고 외부 트랜잭션 중에도 본문은 트랜잭션 밖에서 읽는다")
    void selectsCurrentPassedRevision() {
        long id = fixture("GENERAL", "PUBLIC");
        given(reader.read("test-bucket", "hint-body-" + id, "version-1")).willAnswer(call -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
            return BODY;
        });
        var context = new TransactionTemplate(transactions).execute(status -> {
            var result = service.read(id);
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            return result;
        });
        assertThat(context.revisionId()).isEqualTo(id);
        assertThat(context.title()).isEqualTo("현재 통과 제목");
        assertThat(context.tags()).containsExactly("통과 태그-" + id);
        assertThat(context.body()).contains("설명", "제한", "예시");
        jdbc.update("UPDATE problem_validations SET result_status='PASSED' WHERE id=?", id + 1);
        var next = store.read(id);
        assertThat(next.revisionId()).isEqualTo(id + 1);
        assertThat(next.title()).isEqualTo("새 작성 제목");
        assertThat(next.tags()).containsExactly("새 회차 태그-" + id);
    }

    /**
     * 일반 공개 외의 문제는 회차·태그·본문을 내보내지 않는다.
     *
     * @param scope 문제 용도
     * @param visibility 공개 상태
     */
    @ParameterizedTest
    @CsvSource(
        {
            "GENERAL,PRIVATE",
            "GENERAL,ARCHIVED",
            "EXAM_ONLY,PRIVATE",
            "EXAM_ONLY,PUBLIC"
        }
    )
    @DisplayName("비공개·보관·대회 전용 문제는 종료 공개 여부와 무관하게 힌트 입력을 제외한다")
    void excludesUnavailableProblems(String scope, String visibility) {
        long id = fixture(scope, visibility);
        var result = store.read(id);
        assertThat(result.revisionId()).isNull();
        assertThat(result.title()).isNull();
        assertThat(result.tags()).isEmpty();
        assertThat(result.body()).isNull();
    }

    /** 공개 문제라도 통과 사용 회차가 없으면 가용 입력으로 취급하지 않는다. */
    @Test
    @DisplayName("공개 문제에 통과 회차가 없으면 힌트 입력을 제공하지 않는다")
    void excludesUnvalidatedProblems() {
        long id = fixture("GENERAL", "PUBLIC");
        jdbc.update("UPDATE problem_validations SET result_status='FAILED' WHERE id=?", id);
        assertThat(store.read(id).body()).isNull();
    }

    /** 잘못된 ID와 존재하지 않는 문제를 구분한다. */
    @Test
    @DisplayName("양수가 아닌 ID는 400, 존재하지 않는 문제는 404 오류 코드를 유지한다")
    void rejectsInvalidAndMissingProblems() {
        assertThatThrownBy(() -> store.read(0))
            .isInstanceOfSatisfying(
                ProblemException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_INVALID_REQUEST)
            );
        assertThatThrownBy(() -> store.read(Long.MAX_VALUE))
            .isInstanceOfSatisfying(
                ProblemException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(ProblemErrorCode.PROBLEM_NOT_FOUND)
            );
    }

    /** 통과 회차의 본문 연결이 손상된 경우 비가용 정상 응답으로 숨기지 않는다. */
    @Test
    @DisplayName("가용 회차의 본문 파일이 연결 상태가 아니면 의존 장애로 거절한다")
    void rejectsBrokenBodyReference() {
        long id = fixture("GENERAL", "PUBLIC");
        jdbc.update("UPDATE problem_body_assets SET status='DISCARDED' WHERE id=?", id);
        assertThatThrownBy(() -> store.read(id))
            .isInstanceOfSatisfying(
                ProblemException.class,
                failure -> assertThat(failure.getErrorCode()).isEqualTo(CommonErrorCode.COMMON_DEPENDENCY_UNAVAILABLE)
            );
    }

    /**
     * 현재 통과 회차와 미통과 새 회차·각 회차의 다른 태그를 준비한다.
     *
     * @param scope 문제 용도
     * @param visibility 공개 상태
     * @return 새 문제 식별자
     */
    private long fixture(String scope, String visibility) {
        long id = IDS.getAndAdd(10);
        var content = ProblemAssetContent.fromBytes(BODY);
        jdbc.update("""
            INSERT INTO problems(id,author_id,scope,visibility,row_version,created_at,updated_at)
            VALUES (?,1,?,?,1,now(6),now(6))
            """, id, scope, visibility);
        jdbc.update("""
            INSERT INTO problem_body_assets(id,problem_id,owner_id,status,object_key,object_version,sha256,
                size_bytes,upload_expires_at,created_at,updated_at)
            VALUES (?,?,1,'LINKED',?,'version-1',?,?,now(6),now(6),now(6))
            """, id, id, "hint-body-" + id, content.sha256(), content.sizeBytes());
        for (int i = 0; i < 2; i++) {
            jdbc.update("""
                INSERT INTO problem_revisions(id,problem_id,revision_number,body_asset_id,title,difficulty,
                    function_spec,time_limit_ms,memory_limit_kb,wall_time_limit_ms,output_limit_kb,author_id,
                    idempotency_key,request_hash,response_row_version,created_at)
                VALUES (?,?,?,?,?,1,'{}',2000,262144,7000,64,1,?,repeat('a',64),1,now(6))
                """, id + i, id, i + 1, id, i == 0 ? "현재 통과 제목" : "새 작성 제목", "hint-key-" + (id + i));
            jdbc.update("""
                INSERT INTO tags(id,name,display_order,version,created_at,updated_at) VALUES (?,?,?,0,now(6),now(6))
                """, id + i, (i == 0 ? "통과 태그-" : "새 회차 태그-") + id, id + i);
            jdbc
                .update(
                    "INSERT INTO problem_revision_tags(problem_revision_id,tag_id,created_at) VALUES (?,?,now(6))",
                    id + i,
                    id + i
                );
        }
        jdbc.update("""
            INSERT INTO problem_code_assets(id,problem_id,owner_id,status,object_key,sha256,size_bytes,
                upload_expires_at,created_at,updated_at)
            VALUES (?,?,1,'LINKED',?,repeat('a',64),1,now(6),now(6),now(6))
            """, id, id, "hint-code-" + id);
        for (int i = 0; i < 2; i++) {
            jdbc.update("""
                INSERT INTO problem_validations(id,problem_revision_id,requested_by,language,code_asset_id,
                    trigger_type,request_hash,result_status,created_at,updated_at)
                VALUES (?,?,1,'java',?,'MANUAL',repeat('a',64),?,now(6),now(6))
                """, id + i, id + i, id, i == 0 ? "PASSED" : "FAILED");
        }
        return id;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Beans {
        /**
         * 기존 본문 JSON 표현을 읽는 매퍼를 제공한다.
         *
         * @return JSON 매퍼
         */
        @Bean
        ObjectMapper mapper() {
            return JsonMapper.builder().build();
        }

        /**
         * 원격 읽기의 기존 버킷 설정을 제공한다.
         *
         * @return 본문 버킷 설정
         */
        @Bean
        ProblemImageStorageProperties storage() {
            return new ProblemImageStorageProperties("test-bucket");
        }
    }
}
