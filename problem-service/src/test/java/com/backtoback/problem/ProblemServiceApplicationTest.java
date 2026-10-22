package com.backtoback.problem;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.localstack.LocalStackContainer;
import org.testcontainers.mysql.MySQLContainer;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;

/** 문제 서비스의 데이터베이스·Redis·S3 연결과 상태 조회를 확인한다. */
@SpringBootTest(properties = "spring.flyway.locations=classpath:db/migration,classpath:db/testmigration")
@AutoConfigureMockMvc
@ActiveProfiles("local")
@Testcontainers
class ProblemServiceApplicationTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("problem_db");

    @Container
    static final LocalStackContainer LOCALSTACK
        = new LocalStackContainer("localstack/localstack:3.8").withServices("s3");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine").withExposedPorts(6379);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private Flyway flyway;

    @Autowired
    private S3Client s3;

    @Autowired
    private MockMvc mvc;

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("spring.data.redis.password", () -> "");
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("LOCALSTACK_ENDPOINT", LOCALSTACK::getEndpoint);
        registry.add("spring.cloud.aws.region.static", LOCALSTACK::getRegion);
        registry.add("spring.cloud.aws.credentials.access-key", LOCALSTACK::getAccessKey);
        registry.add("spring.cloud.aws.credentials.secret-key", LOCALSTACK::getSecretKey);
    }

    @Test
    @DisplayName("문제 데이터베이스에 연결하고 마이그레이션을 적용한다")
    void applicationConnectsToProblemDatabaseAndAppliesMigrations() {
        assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("problem_db");
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("1");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM setup_probe", Integer.class)).isZero();
    }

    @Test
    @DisplayName("설정된 S3 클라이언트로 LocalStack 객체를 저장하고 조회한다")
    void configuredS3ClientCanWriteAndReadLocalStack() {
        String bucket = "problem-setup-test";
        s3.createBucket(request -> request.bucket(bucket));
        s3.putObject(request -> request.bucket(bucket).key("probe"), RequestBody.fromString("connected"));
        assertThat(s3.getObjectAsBytes(request -> request.bucket(bucket).key("probe")).asUtf8String())
            .isEqualTo("connected");
    }

    @Test
    @DisplayName("인증 없이 서비스 상태를 조회한다")
    void healthIsAvailableWithoutAuthentication() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

}
