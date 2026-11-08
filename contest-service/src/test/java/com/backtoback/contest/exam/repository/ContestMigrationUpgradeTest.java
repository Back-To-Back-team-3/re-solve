package com.backtoback.contest.exam.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.DriverManager;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.mysql.MySQLContainer;

/** V7까지 적용된 데이터베이스의 후속 마이그레이션을 검증한다. */
@Testcontainers(disabledWithoutDocker = true)
class ContestMigrationUpgradeTest {
    @Container
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4").withDatabaseName("contest_upgrade");

    /** 기존 시험·가드 데이터를 둔 채 요청 보존 컬럼까지 순서대로 적용한다. */
    @Test
    @DisplayName("V7 데이터베이스에 V8부터 V16.4까지 추가 적용한다")
    void migratesFromVersionSeven() throws Exception {
        // given
        Flyway
            .configure()
            .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .target("7")
            .load()
            .migrate();
        try (
            var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
            var statement = connection.createStatement()
        ) {
            statement
                .executeUpdate(
                    "INSERT INTO exams (study_id, creator_id, title, mode, starts_at, ends_at, "
                        + "status, exam_revision, total_score, result_revision, version, created_at, updated_at) "
                        + "VALUES (1, 2, 'existing', 'FIXED', NOW(6), NOW(6), 'SCHEDULED', "
                        + "1, 100, 0, 0, NOW(6), NOW(6))"
                );
        }
        Flyway
            .configure()
            .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
            .target("16")
            .load()
            .migrate();
        try (
            var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
            var statement = connection.createStatement()
        ) {
            statement
                .executeUpdate(
                    "INSERT INTO contest_resource_guards "
                        + "(resource_type, resource_id, state, created_at, updated_at) "
                        + "VALUES ('PROBLEM', 1, 'OPEN', NOW(6), NOW(6))"
                );
        }
        // when
        Flyway.configure().dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword()).load().migrate();
        // then
        try (
            var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
            var statement = connection.createStatement();
            var result
                = statement
                    .executeQuery(
                        "SELECT source_revision_id, source_revision_number, "
                            + "source_supported_languages FROM contest_resource_guards WHERE resource_type='PROBLEM' "
                            + "AND resource_id=1"
                    )
        ) {
            assertThat(result.next()).isTrue();
            assertThat(result.getObject(1)).isNull();
            assertThat(result.getObject(2)).isNull();
            assertThat(result.getObject(3)).isNull();
        }
        try (
            var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
            var statement = connection.createStatement();
            var result = statement.executeQuery("SELECT COUNT(*) FROM exams WHERE title = 'existing'")
        ) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(1);
        }
        try (
            var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
            var statement = connection.createStatement();
            var result
                = statement
                    .executeQuery(
                        "SELECT COUNT(*) FROM flyway_schema_history " + "WHERE success = true AND version IS NOT NULL"
                    )
        ) {
            assertThat(result.next()).isTrue();
            assertThat(result.getInt(1)).isEqualTo(20);
        }
        try (
            var connection = DriverManager.getConnection(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword());
            var statement = connection.createStatement();
            var result = statement.executeQuery("SHOW TABLES LIKE 'contest_study_guard_preparations'")
        ) {
            assertThat(result.next()).isTrue();
        }
    }
}
