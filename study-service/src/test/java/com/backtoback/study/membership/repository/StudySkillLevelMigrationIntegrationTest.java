package com.backtoback.study.membership.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/**
 * 구형 스터디 실력 수준 정규화 마이그레이션 통합 테스트 (정책 및 상태 §7.1 허용 수준, ERD 7.5 Study).
 * Flyway는 빈 테이블에 먼저 적용되므로 구형 값 행을 넣은 뒤 같은 스크립트를 다시 실행해 결과를 검증한다.
 */
@SpringBootTest
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class StudySkillLevelMigrationIntegrationTest {

    private static final String MIGRATION = "db/migration/V20__study_update_studies.sql";
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 1, 2, 3, 4, 5);

    @Container
    @SuppressWarnings("resource")
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("study_db");

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    @DisplayName("LV0은 LV1로, MASTER는 LV3로 바꾸고 다른 값과 수정 시각은 상태와 관계없이 유지한다")
    void normalizesLegacySkillLevels() {
        // given
        Map<Long, String> ids = new LinkedHashMap<>();
        ids.put(insertStudy("LV0", "RECRUITING"), "LV1");
        ids.put(insertStudy("MASTER", "ACTIVE"), "LV3");
        ids.put(insertStudy("LV0", "CLOSED"), "LV1");
        ids.put(insertStudy("LV2", "ACTIVE"), "LV2");
        ids.put(insertStudy("UNKNOWN", "RECRUITING"), "UNKNOWN");

        // when
        jdbcTemplate.execute((ConnectionCallback<Void>)connection -> {
            ScriptUtils.executeSqlScript(connection, new ClassPathResource(MIGRATION));
            return null;
        });

        // then
        ids.forEach((id, expected) -> {
            Map<String, Object> row
                = jdbcTemplate.queryForMap("SELECT skill_level, updated_at FROM studies WHERE id = ?", id);
            assertThat(row.get("skill_level")).isEqualTo(expected);
            assertThat(row.get("updated_at")).isEqualTo(UPDATED_AT);
        });
    }

    private Long insertStudy(String skillLevel, String status) {
        jdbcTemplate
            .update(
                "INSERT INTO studies (name, goal, skill_level, preferred_language, active_time_slot, capacity, "
                    + "member_count, manager_count, join_type, is_auto_reopen, status, version, created_at, "
                    + "updated_at) VALUES ('스터디', 'EMPLOYMENT', ?, 'java', 'EVENING', 10, 1, 0, 'INSTANT', TRUE, ?, "
                    + "0, ?, ?)",
                skillLevel,
                status,
                UPDATED_AT,
                UPDATED_AT
            );
        return jdbcTemplate.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
