package com.backtoback.integration;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
class IntegrationServiceApplicationTests {

    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("integration_db");

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired(required = false)
    private JdbcTemplate jdbc;

    @Autowired(required = false)
    private Flyway flyway;

    @Test
    @DisplayName("DB 연결과 Flyway 마이그레이션 적용 및 스프링 컨텍스트 로드를 검증한다")
    void contextLoads() {
        if (jdbc != null) {
            assertThat(jdbc.queryForObject("SELECT DATABASE()", String.class)).isEqualTo("integration_db");
        }
        if (flyway != null && flyway.info().current() != null) {
            assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("4");
        }
    }

}
