package com.backtoback.ai;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 전체 애플리케이션 컨텍스트가 실제 MySQL(Flyway V1~)과 필수 설정으로 뜨는지 확인한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class AiServiceApplicationTests {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("ai_db");

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        // SQS 리스너는 실제 큐 연결이 필요하므로 컨텍스트 로드 테스트에서 비활성화한다.
        registry.add("spring.cloud.aws.sqs.enabled", () -> "false");
    }

    @Test
    void contextLoads() {}
}
