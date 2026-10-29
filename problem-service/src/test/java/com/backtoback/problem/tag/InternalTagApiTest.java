package com.backtoback.problem.tag;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;

import com.backtoback.problem.tag.domain.Tag;
import com.backtoback.problem.tag.repository.TagRepository;

/** 태그 존재 확인 내부 API(API 명세서 2.6.11)의 응답을 검증한다. */
@SpringBootTest(
    properties = {
        "spring.flyway.locations=classpath:db/migration,classpath:db/testmigration",
        "spring.cloud.aws.region.static=ap-northeast-2",
        "spring.cloud.aws.credentials.access-key=test",
        "spring.cloud.aws.credentials.secret-key=test"
    }
)
@AutoConfigureMockMvc
class InternalTagApiTest {
    private static final String PATH = "/internal/v1/tags/";

    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("problem_db");

    // 공통 Redisson 클라이언트 빈이 컨텍스트 기동 시 Redis 연결을 시도하므로, 락을 쓰지 않는 이 테스트도 함께 띄운다.
    static final GenericContainer<?> REDIS
        = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379)
            .withCommand("redis-server", "--requirepass", "test-password");

    @Autowired
    private MockMvc mvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private TagRepository tags;

    @BeforeAll
    static void startInfrastructure() {
        Assumptions
            .assumeTrue(DockerClientFactory.instance().isDockerAvailable(), "Docker가 없어 태그 내부 API 통합 테스트를 건너뜁니다.");
        MYSQL.start();
        REDIS.start();
    }

    @AfterAll
    static void stopInfrastructure() {
        REDIS.stop();
        MYSQL.stop();
    }

    /**
     * 테스트 컨테이너의 연결 정보를 Spring 설정에 등록한다.
     *
     * @param registry 테스트 실행 시 사용할 동적 속성 등록기
     */
    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "test-password");
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @BeforeEach
    void clearData() {
        jdbc.update("DELETE FROM tags");
    }

    @Test
    @DisplayName("존재하는 태그는 ID와 이름을 응답한다")
    void existingTagReturnsIdAndName() throws Exception {
        Tag tag = tags.saveAndFlush(Tag.create("해시", 0));

        mvc
            .perform(get(PATH + tag.getId()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.tagId").value(tag.getId().toString()))
            .andExpect(jsonPath("$.name").value("해시"));
    }

    @Test
    @DisplayName("없는 태그는 PROBLEM_TAG_NOT_FOUND로 응답한다")
    void missingTagReturnsNotFound() throws Exception {
        mvc
            .perform(get(PATH + "999999"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error.code").value("PROBLEM_TAG_NOT_FOUND"));
    }
}
